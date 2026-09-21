package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.yield
import java.util.UUID

object TrevorCore {
    // Keeps ordinary AI requests small. Local tools still answer first, so many requests use 0 API tokens.
    private const val MAX_HISTORY_MESSAGES = 6
    private const val MAX_HISTORY_ITEM_CHARS = 700
    private const val MAX_PROJECT_ITEMS = 8
    private const val MAX_PROJECT_ITEM_CHARS = 700
    private const val MAX_MEMORY_ITEMS = 4
    private const val MAX_MEMORY_ITEM_CHARS = 500
    private const val MAX_ENRICHED_CHARS = 9_000

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

        TrevorPersistentMemory.migrateLegacyMemories(context)
        val prefs = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
        val conversationId = prefs.getString("conversation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("conversation_id", it).apply()
        }
        TrevorPersistentMemory.saveMessage(context, conversationId, "user", clean, null)

        val localAnswer = TrevorLocalIntelligence.answer(context, clean)
        if (localAnswer != null) return finish(TrevorCoreResult.Answer(localAnswer))

        val remind = Regex("^remind me in\\s+(\\d+)\\s+(second|seconds|minute|minutes|hour|hours)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (remind != null) {
            val amount = remind.groupValues[1].toLong()
            val unit = remind.groupValues[2].lowercase()
            val title = remind.groupValues[3].trim()
            val millis = when {
                unit.startsWith("second") -> amount * 1000L
                unit.startsWith("minute") -> amount * 60_000L
                else -> amount * 3_600_000L
            }
            TrevorTaskEngine.schedule(context, title, title, System.currentTimeMillis() + millis)
            return finish(TrevorCoreResult.Answer("Scheduled locally: $title"))
        }

        val remember = Regex("^remember\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)
        if (remember != null) {
            TrevorPersistentMemory.saveLongTermMemory(context, remember)
            return finish(TrevorCoreResult.Answer("Memory saved locally: $remember"))
        }

        val androidAction = TrevorAndroidActions.tryDispatch(context, clean)
        if (androidAction != null) return finish(TrevorCoreResult.Answer(androidAction.detail))

        val resolvedMode = TrevorModeRouter.route(mode, clean)
        if (resolvedMode == TrevorMode.PROJECT) {
            val projectId = prefs.getString("project_id", "default") ?: "default"
            TrevorPersistentMemory.saveProjectMemory(context, projectId, clean)
        }
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
        val conversationId = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
            .getString("conversation_id", null)

        // Only send a small, recent slice of history. Older context remains in Room but is not
        // repeatedly paid for on every request.
        val history = conversationId?.let {
            TrevorPersistentMemory.recentConversation(context, it, MAX_HISTORY_MESSAGES)
                .takeLast(MAX_HISTORY_MESSAGES)
                .joinToString("\n") { m ->
                    m.role + ": " + m.content.trim().take(MAX_HISTORY_ITEM_CHARS)
                }
        }.orEmpty()

        val projectContext = if (mode == TrevorMode.PROJECT) {
            val projectId = context.getSharedPreferences("trevor_runtime", Context.MODE_PRIVATE)
                .getString("project_id", "default") ?: "default"
            TrevorPersistentMemory.projectMemory(context, projectId)
                .takeLast(MAX_PROJECT_ITEMS)
                .joinToString("\n") { it.content.trim().take(MAX_PROJECT_ITEM_CHARS) }
        } else ""

        // Prefer memories that overlap with the current request instead of dumping the whole
        // long-term memory store into every API call.
        val queryTerms = input.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 }
            .distinct()
            .take(24)

        val memories = TrevorPersistentMemory.longTermMemory(context)
        val relevantMemories = memories
            .sortedByDescending { memory ->
                val saved = memory.content.lowercase()
                queryTerms.count { term -> saved.contains(term) }
            }
            .filter { memory ->
                queryTerms.any { term -> memory.content.lowercase().contains(term) }
            }
            .take(MAX_MEMORY_ITEMS)

        val longTermContext = relevantMemories.joinToString("\n") {
            it.content.trim().take(MAX_MEMORY_ITEM_CHARS)
        }

        val enriched = buildString {
            append(input.trim().take(4_000))
            if (history.isNotBlank()) {
                append("\n\nRecent conversation:\n")
                append(history)
            }
            if (projectContext.isNotBlank()) {
                append("\n\nRelevant project context:\n")
                append(projectContext)
            }
            if (longTermContext.isNotBlank()) {
                append("\n\nRelevant approved memory:\n")
                append(longTermContext)
            }
        }.take(MAX_ENRICHED_CHARS)

        if (mode == TrevorMode.RESEARCH) {
            val geminiKey = SecureApiKeyStore.load(context)
            if (!geminiKey.isNullOrBlank()) {
                val grounded = GeminiAiProvider.ask(
                    context = context.applicationContext,
                    apiKey = geminiKey,
                    prompt = enriched,
                    attachment = attachment,
                    useGoogleSearch = true
                )
                if (grounded.isSuccess) {
                    val verified = TrevorResearchVerifier.appendVerification(grounded.getOrThrow())
                    return TrevorCoreResult.Answer(verified)
                }
            }
        }
        val result = TrevorMultiProviderRouter.ask(
            context = context.applicationContext,
            prompt = enriched,
            preferred = settings.preferredProvider
        )
        return result.fold(
            onSuccess = { TrevorCoreResult.Answer(if (mode == TrevorMode.RESEARCH) TrevorResearchVerifier.appendVerification(it) else it) },
            onFailure = { error -> TrevorCoreResult.Error(TrevorErrorEngine.userMessage(error.message ?: "All configured providers failed or no API key is configured.")) }
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
        val memory = kotlinx.coroutines.runBlocking {
            TrevorPersistentMemory.longTermMemory(context).map { it.content }.filter { saved ->
                input.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.any { term -> saved.lowercase().contains(term) }
            }.take(6)
        }
        return listOf(
            "Mode: " + mode.name,
            if (memory.isNotEmpty()) "Relevant approved local memory:\n- " + memory.joinToString("\n- ") else "",
            modeInstruction,
            style,
            technical,
            attachment?.let { "Attached file: " + it.name + " (" + it.mimeType + "). Use it as authoritative user-provided context." } ?: "",
            "User request:",
            input.take(4_000),
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
