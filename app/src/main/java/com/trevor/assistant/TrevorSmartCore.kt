package com.trevor.assistant

import java.util.Locale

/**
 * Part 4: deterministic intent/context layer used before provider selection.
 * It does not execute Android actions and never grants permissions.
 */
object TrevorSmartCore {
    enum class IntentKind { QUESTION, EXPLANATION, RESEARCH, ANALYSIS, PROJECT, AUTOMATION, TERMINAL, MEMORY, REMINDER, GENERAL }

    data class Intent(
        val kind: IntentKind,
        val confidence: Double,
        val needsFreshInformation: Boolean,
        val needsAdvancedModel: Boolean
    )

    fun classify(input: String, mode: TrevorMode): Intent {
        val text = input.trim().lowercase(Locale.ROOT)
        val kind = when {
            mode == TrevorMode.TERMINAL -> IntentKind.TERMINAL
            mode == TrevorMode.RESEARCH -> IntentKind.RESEARCH
            mode == TrevorMode.ANALYSE -> IntentKind.ANALYSIS
            mode == TrevorMode.PROJECT -> IntentKind.PROJECT
            Regex("""^(remember|save|store)\\b""").containsMatchIn(text) -> IntentKind.MEMORY
            Regex("""^remind me\\b""").containsMatchIn(text) -> IntentKind.REMINDER
            Regex("""\\b(open|launch|start|turn on|turn off|enable|disable|set|share|copy)\\b""").containsMatchIn(text) -> IntentKind.AUTOMATION
            Regex("""\\b(latest|today|current|now|news|recent|this week|look up|search|research|verify)\\b""").containsMatchIn(text) -> IntentKind.RESEARCH
            Regex("""\\b(analy[sz]e|compare|audit|debug|find (the )?error|review|inspect)\\b""").containsMatchIn(text) -> IntentKind.ANALYSIS
            Regex("""\\b(why|how|what|when|where|who|which|can|could|is|are|does|do)\\b""").containsMatchIn(text) -> IntentKind.QUESTION
            Regex("""\\b(explain|define|meaning|difference|teach)\\b""").containsMatchIn(text) -> IntentKind.EXPLANATION
            else -> IntentKind.GENERAL
        }
        val confidence = when (kind) {
            IntentKind.TERMINAL, IntentKind.RESEARCH, IntentKind.ANALYSIS, IntentKind.PROJECT -> 0.95
            IntentKind.AUTOMATION, IntentKind.MEMORY, IntentKind.REMINDER -> 0.90
            IntentKind.QUESTION, IntentKind.EXPLANATION -> 0.82
            IntentKind.GENERAL -> 0.55
        }
        val fresh = kind == IntentKind.RESEARCH || Regex("""\\b(latest|today|current|now|recent|news)\\b""").containsMatchIn(text)
        val advanced = kind == IntentKind.RESEARCH || kind == IntentKind.ANALYSIS || kind == IntentKind.PROJECT || kind == IntentKind.TERMINAL || input.length > 1800
        return Intent(kind, confidence, fresh, advanced)
    }

    fun shouldUseAdvanced(input: String, mode: TrevorMode): Boolean = classify(input, mode).needsAdvancedModel

    fun instruction(input: String, mode: TrevorMode): String {
        val intent = classify(input, mode)
        return "Intent=" + intent.kind.name + "; confidence=" + "%.2f".format(Locale.ROOT, intent.confidence) +
            "; freshInformation=" + intent.needsFreshInformation + "; advancedReasoning=" + intent.needsAdvancedModel + "."
    }
}
