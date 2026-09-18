package com.trevor.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiAiProvider {

    private const val MODEL = "gemini-3.6-flash"

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun ask(
        apiKey: String,
        prompt: String
    ): Result<String> = withContext(Dispatchers.IO) {

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Gemini API key is missing.")
            )
        }

        if (prompt.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Prompt cannot be empty.")
            )
        }

        var connection: HttpURLConnection? = null

        try {
            connection = URL(ENDPOINT).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "x-goog-api-key",
                apiKey
            )

            val requestBody = JSONObject()
                .put(
                    "contents",
                    org.json.JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "parts",
                                    org.json.JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("text", prompt)
                                        )
                                )
                        )
                )
                .toString()

            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val responseText =
                if (responseCode in 200..299) {
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                } else {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: "Unknown Gemini API error."
                }

            if (responseCode !in 200..299) {
                val errorMessage = try {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Gemini API request failed. HTTP $responseCode"
                } catch (_: Exception) {
                    "Gemini API request failed. HTTP $responseCode"
                }

                return@withContext Result.failure(
                    RuntimeException(errorMessage)
                )
            }

            val responseJson = JSONObject(responseText)

            val candidates = responseJson.optJSONArray("candidates")

            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(
                    RuntimeException("Gemini returned no candidates.")
                )
            }

            val content =
                candidates
                    .getJSONObject(0)
                    .optJSONObject("content")

            val parts =
                content?.optJSONArray("parts")

            if (parts == null || parts.length() == 0) {
                return@withContext Result.failure(
                    RuntimeException("Gemini returned no response text.")
                )
            }

            val answer = buildString {

                for (i in 0 until parts.length()) {

                    val part = parts.optJSONObject(i)

                    val text = part?.optString("text")
                        ?.takeIf { it.isNotBlank() }

                    if (text != null) {
                        if (isNotEmpty()) {
                            append("\n")
                        }

                        append(text)
                    }
                }
            }.trim()

            if (answer.isBlank()) {
                Result.failure(
                    RuntimeException("Gemini returned an empty response.")
                )
            } else {
                Result.success(answer)
            }

        } catch (e: Exception) {

            Result.failure(
                RuntimeException(
                    e.message ?: "Unable to connect to Gemini.",
                    e
                )
            )

        } finally {
            connection?.disconnect()
        }
    }
}
