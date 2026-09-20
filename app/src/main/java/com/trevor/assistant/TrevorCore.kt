package com.trevor.assistant

import android.content.Context

/**
 * Central application boundary for TREVOR request processing.
 */
object TrevorCore {

    suspend fun process(
        context: Context,
        command: String,
        aiEnabled: Boolean,
        geminiEnabled: Boolean,
        conciseResponses: Boolean,
        technicalDetail: Boolean,
        mode: TrevorMode? = null
    ): TrevorCoreResult {
        val clean = command.trim()
        if (clean.isBlank()) {
            TrevorStateStore.update {
                it.copy(
                    requestState = TrevorRequestState.ERROR,
                    orbState = TrevorOrbState.ERROR,
                    lastError = "Please enter a command."
                )
            }
            return TrevorCoreResult.Error("Please enter a command.")
        }

        val resolvedMode = TrevorModeRouter.route(mode, clean)

        TrevorStateStore.update {
            it.copy(
                currentMode = resolvedMode,
                requestState = TrevorRequestState.PROCESSING,
                orbState = when (resolvedMode) {
                    TrevorMode.NORMAL -> TrevorOrbState.THINKING
                    TrevorMode.ANALYSE -> TrevorOrbState.ANALYSING
                    TrevorMode.RESEARCH -> TrevorOrbState.RESEARCHING
                    TrevorMode.PROJECT -> TrevorOrbState.THINKING
                    TrevorMode.RATIO_SHIFTER -> TrevorOrbState.THINKING
                },
                aiState = if (aiEnabled && geminiEnabled) {
                    TrevorAiState.READY
                } else {
                    TrevorAiState.DISABLED
                },
                lastError = null
            )
        }

        val result = when (val localResult = TrevorLocalEngine.processCommand(clean)) {
            is TrevorEngineResult.Answer -> TrevorCoreResult.Answer(localResult.text)
            is TrevorEngineResult.Error -> TrevorCoreResult.Error(localResult.message)
            is TrevorEngineResult.NeedAI -> {
                if (!aiEnabled) {
                    TrevorCoreResult.Error("AI is disabled. Enable AI in Settings.")
                } else if (!geminiEnabled) {
                    TrevorCoreResult.Error("Gemini AI is disabled. Enable Gemini in Settings.")
                } else {
                    val key = SecureApiKeyStore.load(context.applicationContext)
                    if (key == null) {
                        TrevorCoreResult.Error(
                            "Gemini API key is not configured.\nOpen Settings → Gemini API Key."
                        )
                    } else {
                        TrevorStateStore.update {
                            it.copy(aiState = TrevorAiState.PROCESSING)
                        }

                        val aiResult = GeminiAiProvider.ask(
                            apiKey = key,
                            prompt = buildPrompt(
                                input = clean,
                                mode = resolvedMode,
                                conciseResponses = conciseResponses,
                                technicalDetail = technicalDetail
                            )
                        )

                        aiResult.fold(
                            onSuccess = { TrevorCoreResult.Answer(it) },
                            onFailure = {
                                TrevorCoreResult.Error(
                                    "Gemini request failed:\n${it.message ?: "Unknown error"}"
                                )
                            }
                        )
                    }
                }
            }
        }

        TrevorStateStore.update {
            when (result) {
                is TrevorCoreResult.Answer -> it.copy(
                    requestState = TrevorRequestState.SUCCESS,
                    orbState = TrevorOrbState.SUCCESS,
                    aiState = if (it.aiState == TrevorAiState.PROCESSING) {
                        TrevorAiState.READY
                    } else {
                        it.aiState
                    },
                    lastError = null
                )

                is TrevorCoreResult.Error -> it.copy(
                    requestState = TrevorRequestState.ERROR,
                    orbState = TrevorOrbState.ERROR,
                    aiState = if (it.aiState == TrevorAiState.PROCESSING) {
                        TrevorAiState.ERROR
                    } else {
                        it.aiState
                    },
                    lastError = result.message
                )
            }
        }

        return result
    }

    private fun buildPrompt(
        input: String,
        mode: TrevorMode,
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

        val modeInstruction = when (mode) {
            TrevorMode.NORMAL ->
                "Act as a general personal assistant. Answer naturally and conversationally."

            TrevorMode.PROJECT ->
                "Treat this as project work. Help plan, build, organize, debug, or reason through the project without requiring rigid command syntax."

            TrevorMode.RESEARCH ->
                "Treat this as research. Distinguish established facts, uncertainty, and claims that need sources. Do not pretend to have searched when no search tool was used."

            TrevorMode.ANALYSE ->
                "Treat this as analysis. Carefully inspect the supplied information, identify patterns, errors, assumptions, and useful conclusions."

            TrevorMode.RATIO_SHIFTER ->
                "Treat this as an interface/layout adaptation request. Focus on how TREVOR should reflow or adapt its presentation."
        }

        return """
            You are TREVOR, The Riteshified Efficient Virtual Operation Robot.

            Operating mode: ${mode.name}

            $modeInstruction

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
