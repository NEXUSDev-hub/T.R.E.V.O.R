package com.trevor.assistant

import android.content.Context

object TrevorCore {
    suspend fun process(
        context: Context,
        command: String,
        aiEnabled: Boolean,
        geminiEnabled: Boolean,
        conciseResponses: Boolean,
        technicalDetail: Boolean,
        offlineFirst: Boolean = true,
        mode: TrevorMode? = null
    ): TrevorCoreResult {
        val clean = command.trim()
        if (clean.isBlank()) return finish(TrevorCoreResult.Error("Please enter a command."))

        val resolvedMode = TrevorModeRouter.route(mode, clean)
        TrevorStateStore.update {
            it.copy(
                currentMode = resolvedMode,
                requestState = TrevorRequestState.PROCESSING,
                orbState = when (resolvedMode) {
                    TrevorMode.NORMAL, TrevorMode.PROJECT, TrevorMode.RATIO_SHIFTER -> TrevorOrbState.THINKING
                    TrevorMode.ANALYSE -> TrevorOrbState.ANALYSING
                    TrevorMode.RESEARCH -> TrevorOrbState.RESEARCHING
                },
                aiState = if (aiEnabled && geminiEnabled) TrevorAiState.READY else TrevorAiState.DISABLED,
                lastError = null
            )
        }

        val result = when (val local = TrevorLocalEngine.processCommand(clean)) {
            is TrevorEngineResult.Answer -> TrevorCoreResult.Answer(local.text)
            is TrevorEngineResult.Error -> TrevorCoreResult.Error(local.message)
            is TrevorEngineResult.NeedAI -> when {
                !aiEnabled -> TrevorCoreResult.Error("AI is disabled. Enable AI in Settings.")
                !geminiEnabled -> TrevorCoreResult.Error("Gemini AI is disabled. Enable Gemini in Settings.")
                else -> {
                    val key = SecureApiKeyStore.load(context.applicationContext)
                    if (key.isNullOrBlank()) {
                        TrevorCoreResult.Error("Gemini API key is not configured.\nOpen Settings → Gemini API Key.")
                    } else {
                        TrevorStateStore.update { it.copy(aiState = TrevorAiState.PROCESSING) }
                        GeminiAiProvider.ask(
                            apiKey = key,
                            prompt = buildPrompt(clean, resolvedMode, conciseResponses, technicalDetail)
                        ).fold(
                            onSuccess = { TrevorCoreResult.Answer(it) },
                            onFailure = { error -> TrevorCoreResult.Error("Gemini request failed:\n" + (error.message ?: "Unknown error")) }
                        )
                    }
                }
            }
        }
        return finish(result)
    }

    private fun finish(result: TrevorCoreResult): TrevorCoreResult {
        TrevorStateStore.update {
            when (result) {
                is TrevorCoreResult.Answer -> it.copy(
                    requestState = TrevorRequestState.SUCCESS,
                    orbState = TrevorOrbState.SUCCESS,
                    aiState = if (it.aiState == TrevorAiState.PROCESSING) TrevorAiState.READY else it.aiState,
                    lastError = null
                )
                is TrevorCoreResult.Error -> it.copy(
                    requestState = TrevorRequestState.ERROR,
                    orbState = TrevorOrbState.ERROR,
                    aiState = if (it.aiState == TrevorAiState.PROCESSING) TrevorAiState.ERROR else it.aiState,
                    lastError = result.message
                )
            }
        }
        return result
    }

    private fun buildPrompt(input: String, mode: TrevorMode, conciseResponses: Boolean, technicalDetail: Boolean): String {
        val responseStyle = if (conciseResponses) "Keep responses concise while still answering correctly." else "Give a reasonably detailed response."
        val technicalStyle = if (technicalDetail) "Technical details are welcome when useful." else "Prefer simple explanations and avoid unnecessary technical detail."
        val modeInstruction = when (mode) {
            TrevorMode.NORMAL -> "Act as a general personal assistant. Answer naturally and conversationally."
            TrevorMode.PROJECT -> "Treat this as project work. Help plan, build, organize, debug, or reason through the project."
            TrevorMode.RESEARCH -> "Treat this as research. Distinguish established facts, uncertainty, and claims that need sources. Do not pretend to have searched."
            TrevorMode.ANALYSE -> "Treat this as analysis. Inspect supplied information, identify patterns, errors, assumptions, and useful conclusions."
            TrevorMode.RATIO_SHIFTER -> "Treat this as an interface/layout adaptation request."
        }
        return listOf(
            "You are TREVOR, The Really Efficient Virtual Operation Robot.",
            "TREVOR was created by Abhirup Gupta and Ritesh.",
            "Operating mode: " + mode.name,
            modeInstruction,
            "User request:",
            input,
            responseStyle,
            technicalStyle,
            "Never claim that an action was performed unless it was actually performed and verified."
        ).joinToString("\n")
    }
}

sealed interface TrevorCoreResult {
    data class Answer(val text: String) : TrevorCoreResult
    data class Error(val message: String) : TrevorCoreResult
}
