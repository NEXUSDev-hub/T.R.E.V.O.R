package com.trevor.assistant

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiAiProvider {
    const val MODEL = "gemini-3.8-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val MAX_INLINE_FILE_BYTES = 20L * 1024L * 1024L

    suspend fun ask(
        context: Context,
        apiKey: String,
        prompt: String,
        attachment: TrevorAttachment? = null,
        useGoogleSearch: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("Gemini API key is missing."))
        if (prompt.isBlank()) return@withContext Result.failure(IllegalArgumentException("Prompt cannot be empty."))

        var connection: HttpURLConnection? = null
        try {
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            attachment?.let { file ->
                val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Unable to read the attached file.")
                if (bytes.size > MAX_INLINE_FILE_BYTES) {
                    throw IllegalArgumentException("Attached file is larger than the 20 MB inline limit.")
                }
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", file.mimeType)
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    )
                )
            }

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))

            if (useGoogleSearch) {
                body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }

            connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val responseText = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Gemini API error."
            }

            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Gemini API request failed. HTTP $code"
                return@withContext Result.failure(RuntimeException(message))
            }

            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(RuntimeException("Gemini returned no candidates."))
            }

            val answer = buildString {
                val responseParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                if (responseParts != null) {
                    for (i in 0 until responseParts.length()) {
                        responseParts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append("\n")
                            append(it)
                        }
                    }
                }
                if (useGoogleSearch) {
                    val chunks = candidates.getJSONObject(0).optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                    val urls = mutableListOf<String>()
                    if (chunks != null) {
                        for (i in 0 until chunks.length()) {
                            val web = chunks.optJSONObject(i)?.optJSONObject("web")
                            val uri = web?.optString("uri")?.takeIf { it.startsWith("http") }
                            if (uri != null && uri !in urls) urls += uri
                        }
                    }
                    if (urls.isNotEmpty()) {
                        append("\n\nGoogle sources checked:\n")
                        urls.take(6).forEach { append("• ").append(it).append("\n") }
                    }
                }
            }.trim()

            if (answer.isBlank()) Result.failure(RuntimeException("Gemini returned an empty response."))
            else Result.success(answer)
        } catch (e: Exception) {
            Result.failure(RuntimeException(e.message ?: "Unable to connect to Gemini.", e))
        } finally {
            connection?.disconnect()
        }
    }
}
