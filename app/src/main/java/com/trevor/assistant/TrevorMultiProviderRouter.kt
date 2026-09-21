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
        val autoSwitch = TrevorSettingsStore.load(context).autoProviderSwitch
        val order = buildList {
            preferred?.let(::add)
            if (autoSwitch) {
                TrevorProviderRegistry.providers.map { it.id }.filter { it != preferred }.forEach(::add)
            }
        }
        var last: Result<String> = Result.failure(IllegalStateException("No configured AI provider is available."))
        for (provider in order) {
            if (System.currentTimeMillis() < (cooldownUntil[provider] ?: 0L)) continue
            if (!TrevorProviderLimitTracker.allow(context, provider)) continue
            val key = TrevorProviderKeyStore.load(context, provider) ?: continue
            val spec = TrevorProviderRegistry.spec(provider)
            for (model in spec.models) {
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
        val spec = TrevorProviderRegistry.spec(provider)
        return TrevorHttpProvider(provider).ask(context, key, spec.models.first(), prompt)
    }

    private fun isTemporary(error: Throwable?): Boolean {
        val m = error?.message.orEmpty().lowercase()
        return m.contains("429") || m.contains("rate") || m.contains("quota") ||
            m.contains("timeout") || m.contains("temporar") || m.contains("502") || m.contains("503")
    }
}

private class TrevorHttpProvider(private val provider: TrevorProviderId) {
    suspend fun ask(context: Context, key: String, model: TrevorModelSpec, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val spec = TrevorProviderRegistry.spec(provider)
                when (spec.protocol) {
                    TrevorProviderProtocol.OPENAI_COMPATIBLE -> openAi(spec.endpoint, key, model.id, prompt)
                    TrevorProviderProtocol.ANTHROPIC -> anthropic(spec.endpoint, key, model.id, prompt)
                    TrevorProviderProtocol.COHERE -> cohere(spec.endpoint, key, model.id, prompt)
                    TrevorProviderProtocol.GEMINI -> gemini(spec.endpoint, key, model.id, prompt)
                }
            }.fold({ Result.success(it) }, { Result.failure(RuntimeException(it.message ?: "Provider request failed.", it)) })
        }

    private fun openAi(base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val response = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        val json = JSONObject(response)
        val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun anthropic(base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val json = JSONObject(post("$base/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body))
        val text = json.optJSONArray("content")?.optJSONObject(0)?.optString("text")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun cohere(base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val json = JSONObject(post("$base/chat", mapOf("Authorization" to "Bearer $key"), body))
        val text = json.optString("text").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("message")?.optJSONArray("content")?.optJSONObject(0)?.optString("text")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun gemini(base: String, key: String, model: String, prompt: String): String {
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        ))
        val json = JSONObject(post("$base/models/$model:generateContent", mapOf("x-goog-api-key" to key), body))
        val text = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
        return require(!text.isNullOrBlank()) { responseMessage(json) }.let { text }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
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
