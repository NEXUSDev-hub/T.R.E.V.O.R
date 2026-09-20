package com.trevor.assistant

import android.content.Context

/**
 * Central application boundary for TREVOR request processing.
 *
 * UI code should submit user intent here rather than calling local/AI
 * providers directly. Future modes, memory, files and Android actions can
 * plug into this boundary without expanding MainActivity.
 */
object TrevorCore {
    suspend fun process(
        context: Context,
        command: String,
        aiEnabled: Boolean,
        geminiEnabled: Boolean,
        conciseResponses: Boolean,
        technicalDetail: Boolean
    ): TrevorCoreResult {
        val clean = command.trim()
        if (clean.isBlank()) return TrevorCoreResult.Error("Please enter a command.")

        return when (val localResult = TrevorLocalEngine.processCommand(clean)) {
            is TrevorEngineResult.Answer -> TrevorCoreResult.Answer(localResult.text)
            is TrevorEngineResult.Error -> TrevorCoreResult.Error(localResult.message)
            is TrevorEngineResult.NeedAI -> {
                if (!aiEnabled) return TrevorCoreResult.Error("AI is disabled. Enable AI in Settings.")
                if (!geminiEnabled) return TrevorCoreResult.Error("Gemini AI is disabled. Enable Gemini in Settings.")

                val key = SecureApiKeyStore.load(context.applicationContext)
                    ?: return TrevorCoreResult.Error("Gemini API key is not configured.\nOpen Settings → Gemini API Key.")

                val result = GeminiAiProvider.ask(
                    apiKey = key,
                    prompt = buildPrompt(clean, conciseResponses, technicalDetail)
                )

                result.fold(
                    onSuccess = { TrevorCoreResult.Answer(it) },
                    onFailure = { TrevorCoreResult.Error("Gemini request failed:\n${it.message ?: "Unknown error"}") }
                )
            }
        }
    }

    private fun buildPrompt(
        input: String,
        conciseResponses: Boolean,
        technicalDetail: Boolean
    ): String {
        val responseStyle = if (conciseResponses) {
            "Keep responses concise while still answering correctly."
        } else {
            "Give a reasonably detailed response."
        }

        val technicalStyle = if (technicalDetail) {
            "Technical details are welcome when useful."
        } else {
            "Prefer simple explanations and avoid unnecessary technical detail."
        }

        return """
            You are TREVOR, The Riteshified Efficient Virtual Operation Robot.

            User request:
            $input

            $responseStyle
            $technicalStyle

            Never claim that an action was performed unless it was actually performed and verified.
        """.trimIndent()
    }
}

sealed interface TrevorCoreResult {
    data class Answer(val text: String) : TrevorCoreResult
    data class Error(val message: String) : TrevorCoreResult
}
