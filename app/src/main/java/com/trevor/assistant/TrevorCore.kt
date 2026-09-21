package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.yield
import java.util.UUID

object TrevorCore {
    suspend fun process(
        context: Context,
        command: String,
        aiEnabled: Boolean,
        geminiEnabled: Boolean,
        conciseResponses: Boolean,
        technicalDetail: Boolean,
        offlineFirst: Boolean = true,
        mode: TrevorMode? = null,
        attachment: TrevorAttachment? = null
    ): TrevorCoreResult {
        val clean = command.trim()
        if (clean.isBlank()) return finish(TrevorCoreResult.Error("Please enter a command."))

        val prefs = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
        val conversationId = prefs.getString("conversation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("conversation_id", it).apply()
        }
        TrevorPersistentMemory.saveMessage(context, conversationId, "user", clean, null)

        val remember = Regex("^remember\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)
        if (remember != null) {
            TrevorMemoryStore.add(context, remember)
            TrevorPersistentMemory.saveLongTermMemory(context, remember)
            return finish(TrevorCoreResult.Answer("Memory saved locally: $remember"))
        }

        val resolvedMode = TrevorModeRouter.route(mode, clean)
        TrevorStateStore.update {
            it.copy(
                currentMode = resolvedMode,
                requestState = TrevorRequestState.PROCESSING,
                orbState = when (resolvedMode) {
                    TrevorMode.ANALYSE -> TrevorOrbState.ANALYSING
                    TrevorMode.RESEARCH -> TrevorOrbState.RESEARCHING
                    TrevorMode.TERMINAL -> TrevorOrbState.EXECUTING
                    else -> TrevorOrbState.THINKING
                },
                aiState = if (aiEnabled) TrevorAiState.READY else TrevorAiState.DISABLED,
                lastOutput = null,
                lastError = null
            )
        }
        yield()

        if (resolvedMode == TrevorMode.TERMINAL) {
            val result = TrevorTerminalService.execute(context, clean, TrevorStateStore.state.value)
            TrevorStateStore.update { it.copy(terminalOutput = if (clean.equals("clear", true)) "" else result) }
            TrevorPersistentMemory.saveMessage(context, conversationId, "assistant", result, "LOCAL")
            return finish(TrevorCoreResult.Answer(result))
        }

        val prompt = buildPrompt(context, clean, resolvedMode, conciseResponses, technicalDetail, attachment)
        val result = if (offlineFirst && attachment == null && resolvedMode == TrevorMode.NORMAL) {
            when (val local = TrevorLocalEngine.processCommand(clean)) {
                is TrevorEngineResult.Answer -> TrevorCoreResult.Answer(local.text)
                is TrevorEngineResult.Error -> TrevorCoreResult.Error(local.message)
                is TrevorEngineResult.NeedAI -> requestAi(context, local.prompt, resolvedMode, aiEnabled, geminiEnabled, conciseResponses, technicalDetail, attachment)
            }
        } else {
            val aiResult = requestAi(context, prompt, resolvedMode, aiEnabled, geminiEnabled, conciseResponses, technicalDetail, attachment)
            if (aiResult is TrevorCoreResult.Answer) aiResult
            else if (offlineFirst && attachment == null) {
                when (val local = TrevorLocalEngine.processCommand(clean)) {
                    is TrevorEngineResult.Answer -> TrevorCoreResult.Answer(local.text)
                    else -> aiResult
                }
            } else aiResult
        }

        if (result is TrevorCoreResult.Answer) {
            TrevorPersistentMemory.saveMessage(context, conversationId, "assistant", result.text, "TREVOR")
        }
        return finish(result)
    }

    private suspend fun requestAi(
        context: Context,
        input: String,
        mode: TrevorMode,
        aiEnabled: Boolean,
        geminiEnabled: Boolean,
        conciseResponses: Boolean,
        technicalDetail: Boolean,
        attachment: TrevorAttachment?
    ): TrevorCoreResult {
        if (!aiEnabled) return TrevorCoreResult.Error("AI is disabled. Enable AI in Settings.")

        TrevorStateStore.update { it.copy(aiState = TrevorAiState.PROCESSING) }
        val settings = TrevorSettingsStore.load(context.applicationContext)
        val conversationId = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE).getString("conversation_id", null)
        val history = conversationId?.let {
            TrevorPersistentMemory.recentConversation(context, it, 12)
                .joinToString("\n") { m -> m.role + ": " + m.content }
        }.orEmpty()
        val enriched = buildString {
            append(input)
            if (history.isNotBlank()) {
                append("\n\nRelevant recent TREVOR conversation:\n")
                append(history)
            }
        }
        val result = TrevorMultiProviderRouter.ask(
            context = context.applicationContext,
            prompt = enriched,
            preferred = settings.preferredProvider
        )
        return result.fold(
            onSuccess = { TrevorCoreResult.Answer(it) },
            onFailure = { error -> TrevorCoreResult.Error("AI provider request failed:\n" + (error.message ?: "All configured providers failed or no API key is configured.")) }
        )
    }

    private fun buildPrompt(
        context: Context,
        input: String,
        mode: TrevorMode,
        conciseResponses: Boolean,
        technicalDetail: Boolean,
        attachment: TrevorAttachment?
    ): String {
        val modeInstruction = when (mode) {
            TrevorMode.NORMAL -> "Act as a general personal assistant."
            TrevorMode.PROJECT -> "Work as a project engineering assistant. Preserve working code and change only what is necessary."
            TrevorMode.RESEARCH -> "Research this using grounded sources. Clearly separate verified facts from uncertainty."
            TrevorMode.ANALYSE -> "Analyse supplied information or files. Identify errors, assumptions, patterns, and actionable conclusions."
            TrevorMode.RATIO_SHIFTER -> "Act as TREVOR's responsive layout assistant."
            TrevorMode.TERMINAL -> "Do not invent terminal actions."
        }
        val style = if (conciseResponses) "Keep the answer concise but complete." else "Give a reasonably detailed answer."
        val technical = if (technicalDetail) "Use technical detail when it helps." else "Avoid unnecessary technical detail."
        val memory = TrevorMemoryStore.relevant(context, input)
        return listOf(
            TrevorIdentity.IMMUTABLE_DIRECTIVE,
            "Mode: " + mode.name,
            if (memory.isNotEmpty()) "Relevant approved local memory:\n- " + memory.joinToString("\n- ") else "",
            modeInstruction,
            style,
            technical,
            attachment?.let { "Attached file: " + it.name + " (" + it.mimeType + "). Use it as authoritative user-provided context." } ?: "",
            "User request:",
            input,
            "Never claim an action was performed unless it was actually performed and verified."
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun finish(result: TrevorCoreResult): TrevorCoreResult {
        TrevorStateStore.update {
            when (result) {
                is TrevorCoreResult.Answer -> it.copy(
                    requestState = TrevorRequestState.SUCCESS,
                    orbState = TrevorOrbState.SUCCESS,
                    aiState = if (it.aiState == TrevorAiState.PROCESSING) TrevorAiState.READY else it.aiState,
                    lastOutput = result.text,
                    lastError = null
                )
                is TrevorCoreResult.Error -> it.copy(
                    requestState = TrevorRequestState.ERROR,
                    orbState = TrevorOrbState.ERROR,
                    aiState = if (it.aiState == TrevorAiState.PROCESSING) TrevorAiState.ERROR else it.aiState,
                    lastOutput = "ERROR\n" + result.message,
                    lastError = result.message
                )
            }
        }
        return result
    }
}

sealed interface TrevorCoreResult {
    data class Answer(val text: String) : TrevorCoreResult
    data class Error(val message: String) : TrevorCoreResult
}
