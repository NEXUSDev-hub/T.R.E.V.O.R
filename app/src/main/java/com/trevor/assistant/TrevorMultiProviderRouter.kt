package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object TrevorMultiProviderRouter {
    private val cooldownUntil = ConcurrentHashMap<TrevorProviderId, Long>()
    private const val COOLDOWN_MS = 60_000L

    suspend fun ask(context: Context, prompt: String, preferred: TrevorProviderId? = null): Result<String> {
        val settings = TrevorSettingsStore.load(context)
        val autoSwitch = settings.autoProviderSwitch
        val allowed = TrevorProviderRegistry.providers.map { it.id }
            .filter { it != TrevorProviderId.GEMINI || settings.geminiEnabled }
        val order = buildList {
            preferred?.takeIf { it in allowed }?.let(::add)
            if (autoSwitch) {
                allowed.filter { it != preferred }.forEach(::add)
            }
        }
        var last: Result<String> = Result.failure(IllegalStateException("No configured AI provider is available."))
        for (provider in order) {
            if (System.currentTimeMillis() < (cooldownUntil[provider] ?: 0L)) continue
            if (!TrevorProviderLimitTracker.allow(context, provider)) continue
            val key = TrevorProviderKeyStore.load(context, provider) ?: continue
            val spec = TrevorProviderRegistry.spec(provider)
            val selected = if (provider == settings.preferredProvider)
                TrevorProviderRegistry.findModel(provider, settings.selectedModelId)
            else null
            val models = buildList {
                selected?.let(::add)
                spec.models.filter { it.id != selected?.id }.forEach(::add)
            }
            for (model in models) {
                TrevorProviderLimitTracker.recordRequest(context, provider)
                val result = TrevorHttpProvider(provider).ask(context, key, model, prompt)
                if (result.isSuccess) {
                    cooldownUntil.remove(provider)
                    return result
                }
                last = result
                TrevorProviderLimitTracker.recordFailure(context, provider, result.exceptionOrNull())
                if (isTemporary(result.exceptionOrNull())) {
                    cooldownUntil[provider] = System.currentTimeMillis() + COOLDOWN_MS
                    break
                }
            }
        }
        return last
    }

    suspend fun testSingle(context: Context, provider: TrevorProviderId, prompt: String = "Reply with exactly: TREVOR connection test successful."): Result<String> {
        val key = TrevorProviderKeyStore.load(context, provider) ?: return Result.failure(IllegalStateException("API key is not configured."))
        val settings = TrevorSettingsStore.load(context)
        val spec = TrevorProviderRegistry.spec(provider)
        val selected = if (provider == settings.preferredProvider)
            TrevorProviderRegistry.findModel(provider, settings.selectedModelId)
        else null
        return TrevorHttpProvider(provider).ask(context, key, selected ?: spec.models.first(), prompt)
    }

    private fun isTemporary(error: Throwable?): Boolean {
        val m = error?.message.orEmpty().lowercase()
        return m.contains("429") || m.contains("rate") || m.contains("quota") ||
            m.contains("timeout") || m.contains("temporar") || m.contains("502") || m.contains("503") || m.contains("504")
    }
}

private class TrevorHttpProvider(private val provider: TrevorProviderId) {
    suspend fun ask(context: Context, key: String, model: TrevorModelSpec, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val spec = TrevorProviderRegistry.spec(provider)
                when (spec.protocol) {
                    TrevorProviderProtocol.OPENAI_COMPATIBLE -> openAi(context, spec.endpoint, key, model.id, prompt)
                    TrevorProviderProtocol.ANTHROPIC -> anthropic(context, spec.endpoint, key, model.id, prompt)
                    TrevorProviderProtocol.COHERE -> cohere(context, spec.endpoint, key, model.id, prompt)
                    TrevorProviderProtocol.GEMINI -> gemini(context, spec.endpoint, key, model.id, prompt)
                }
            }.fold({ Result.success(it) }, { Result.failure(RuntimeException(it.message ?: "Provider request failed.", it)) })
        }

    private fun openAi(context: Context, base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val response = post(context, "$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        val json = JSONObject(response)
        val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun anthropic(context: Context, base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val json = JSONObject(post(context, "$base/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body))
        val text = json.optJSONArray("content")?.optJSONObject(0)?.optString("text")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun cohere(context: Context, base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val json = JSONObject(post(context, "$base/chat", mapOf("Authorization" to "Bearer $key"), body))
        val text = json.optString("text").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("message")?.optJSONArray("content")?.optJSONObject(0)?.optString("text")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun gemini(context: Context, base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        ))
        val json = JSONObject(post(context, "$base/models/$model:generateContent", mapOf("x-goog-api-key" to key), body))
        val text = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun post(context: Context, url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            if (code !in 200..299) error("HTTP $code retryAfter=${c.getHeaderField("Retry-After") ?: ""}: $text")
            val remainingRequests = c.getHeaderField("x-ratelimit-remaining-requests")?.toLongOrNull()
                ?: c.getHeaderField("ratelimit-remaining")?.toLongOrNull()
            val remainingTokens = c.getHeaderField("x-ratelimit-remaining-tokens")?.toLongOrNull()
            val resetSeconds = c.getHeaderField("x-ratelimit-reset-requests")?.toLongOrNull()
            val resetAt = resetSeconds?.let { System.currentTimeMillis() + it * 1000L }
            TrevorProviderLimitTracker.recordAuthoritativeHeaders(context, provider, remainingRequests, remainingTokens, resetAt)
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun responseMessage(json: JSONObject): String =
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
            ?: "Provider returned no usable text."
}
