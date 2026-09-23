package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** Part 4: semantic intent routing. Online requests use Gemini structured classification; offline mode has a safe fallback. No reasoning is exposed. */
object TrevorSmartCore {
    enum class IntentKind { QUESTION, EXPLANATION, RESEARCH, ANALYSIS, PROJECT, AUTOMATION, TERMINAL, MEMORY, REMINDER, GENERAL }

    data class Intent(
        val kind: IntentKind,
        val confidence: Double,
        val needsFreshInformation: Boolean,
        val needsAdvancedModel: Boolean,
        val suggestedMode: TrevorMode = TrevorMode.NORMAL,
        val automationSteps: List<TrevorAutomationStep> = emptyList(),
        val evidence: List<String> = emptyList()
    )

    fun classify(input: String, mode: TrevorMode? = null, attachmentPresent: Boolean = false): Intent =
        deterministicFallback(input, mode, attachmentPresent)

    suspend fun understand(
        context: Context,
        input: String,
        mode: TrevorMode? = null,
        attachmentPresent: Boolean = false,
        aiEnabled: Boolean = true,
        geminiEnabled: Boolean = true
    ): Intent = withContext(Dispatchers.IO) {
        val fallback = deterministicFallback(input, mode, attachmentPresent)
        if (!aiEnabled || !geminiEnabled || input.isBlank()) return@withContext fallback
        if (mode != null && mode != TrevorMode.NORMAL && mode != TrevorMode.RATIO_SHIFTER) {
            return@withContext fallback.copy(confidence = 0.99, suggestedMode = mode, evidence = listOf("explicit-mode"))
        }
        if (SecureApiKeyStore.load(context).isNullOrBlank()) return@withContext fallback
        val prompt = "Understand this request by meaning rather than keyword matching. Request: ${input.take(8000)}" +
            if (attachmentPresent) "\nAn attachment is present." else ""
        TrevorMultiProviderRouter.classifyIntent(context, prompt).fold(
            onSuccess = { parseModelIntent(it, attachmentPresent, mode, fallback) },
            onFailure = { fallback }
        )
    }

    fun shouldUseAdvanced(input: String, mode: TrevorMode? = null, attachmentPresent: Boolean = false): Boolean =
        classify(input, mode, attachmentPresent).needsAdvancedModel

    fun instruction(input: String, mode: TrevorMode, attachmentPresent: Boolean = false, understood: Intent? = null): String {
        val i = understood ?: classify(input, mode, attachmentPresent)
        return "Intent=${i.kind.name}; confidence=${"%.2f".format(Locale.ROOT, i.confidence)}; " +
            "freshInformation=${i.needsFreshInformation}; advancedReasoning=${i.needsAdvancedModel}."
    }

    private fun parseModelIntent(raw: String, attachmentPresent: Boolean, explicitMode: TrevorMode?, fallback: Intent): Intent = runCatching {
        val json = JSONObject(raw)
        val kind = IntentKind.valueOf(json.optString("kind", "GENERAL"))
        val confidence = json.optDouble("confidence", fallback.confidence).coerceIn(0.0, 1.0)
        val fresh = json.optBoolean("needsFreshInformation", kind == IntentKind.RESEARCH)
        val advanced = attachmentPresent || json.optBoolean("needsAdvancedModel", kind in setOf(IntentKind.RESEARCH, IntentKind.ANALYSIS, IntentKind.PROJECT, IntentKind.TERMINAL))
        val modelMode = runCatching { TrevorMode.valueOf(json.optString("mode", TrevorMode.NORMAL.name).uppercase(Locale.ROOT)) }.getOrDefault(fallback.suggestedMode)
        val actions = json.optJSONArray("actions")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TrevorAutomationStep(
                            action = item.optString("action").uppercase(Locale.ROOT),
                            argument = item.optString("argument"),
                            requiresForeground = item.optBoolean("requiresForeground", false),
                            verify = item.optString("verify")
                        )
                    )
                }
            }
        }.orEmpty()
        val safeActions = actions.take(20).filter {
            TrevorStructuredPlanner.validate(
                TrevorAutomationPlan("semantic", listOf(it), "semantic")
            ).valid
        }
        Intent(kind, confidence, fresh, advanced, explicitMode ?: modelMode, safeActions, listOf("natural-language-model"))
    }.getOrElse { fallback }

    private fun deterministicFallback(input: String, mode: TrevorMode?, attachmentPresent: Boolean): Intent {
        val text = input.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return Intent(IntentKind.GENERAL, 1.0, false, attachmentPresent, mode ?: TrevorMode.NORMAL, emptyList(), listOf("blank-input"))
        val explicit = when (mode) {
            TrevorMode.TERMINAL -> Intent(IntentKind.TERMINAL, .99, false, true, TrevorMode.TERMINAL, emptyList(), listOf("explicit-mode"))
            TrevorMode.RESEARCH -> Intent(IntentKind.RESEARCH, .99, true, true, TrevorMode.RESEARCH, emptyList(), listOf("explicit-mode"))
            TrevorMode.ANALYSE -> Intent(IntentKind.ANALYSIS, .99, false, true, TrevorMode.ANALYSE, emptyList(), listOf("explicit-mode"))
            TrevorMode.PROJECT -> Intent(IntentKind.PROJECT, .99, false, true, TrevorMode.PROJECT, emptyList(), listOf("explicit-mode"))
            else -> null
        }
        if (explicit != null) return explicit.copy(suggestedMode = mode ?: TrevorMode.NORMAL)
        val kind = when {
            Regex("""^(remember|save|store)\\b""").containsMatchIn(text) -> IntentKind.MEMORY
            Regex("""^remind me\\b""").containsMatchIn(text) -> IntentKind.REMINDER
            Regex("""\\b(run|execute)\\b.{0,40}\\bterminal\\b|\\bshell command\\b""").containsMatchIn(text) -> IntentKind.TERMINAL
            Regex("""\\b(research|verify|look up|latest|current|recent|news)\\b""").containsMatchIn(text) -> IntentKind.RESEARCH
            Regex("""\\b(analy[sz]e|audit|debug|inspect|diagnos|compare|review)\\w*\\b""").containsMatchIn(text) -> IntentKind.ANALYSIS
            Regex("""\\b(project|architecture|roadmap|prototype)\\b""").containsMatchIn(text) -> IntentKind.PROJECT
            Regex("""\\b(open|launch|turn on|turn off|enable|disable|share|copy)\\b""").containsMatchIn(text) -> IntentKind.AUTOMATION
            Regex("""\\b(explain|define|meaning|difference|teach me)\\b""").containsMatchIn(text) -> IntentKind.EXPLANATION
            text.endsWith("?") || Regex("""^(what|why|how|when|where|who|which|can|could|is|are|does|do)\\b""").containsMatchIn(text) -> IntentKind.QUESTION
            else -> IntentKind.GENERAL
        }
        val fresh = kind == IntentKind.RESEARCH || Regex("""\\b(latest|today|current|right now|recent|news|verify)\\b""").containsMatchIn(text)
        val advanced = attachmentPresent || kind in setOf(IntentKind.RESEARCH, IntentKind.ANALYSIS, IntentKind.PROJECT, IntentKind.TERMINAL) || input.length > 1800
        val suggestedMode = when (kind) {
            IntentKind.RESEARCH -> TrevorMode.RESEARCH
            IntentKind.ANALYSIS -> TrevorMode.ANALYSE
            IntentKind.PROJECT -> TrevorMode.PROJECT
            IntentKind.TERMINAL -> TrevorMode.TERMINAL
            else -> TrevorMode.NORMAL
        }
        return Intent(kind, .62, fresh, advanced, suggestedMode, emptyList(), listOf("offline-fallback"))
    }
}
