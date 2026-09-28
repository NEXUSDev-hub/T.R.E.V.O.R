package com.trevor.assistant

import android.content.Context

data class TrevorAiDecision(
    val model: String,
    val useSearch: Boolean,
    val reason: String
)

/** Single fixed AI engine. Model selection is intentionally impossible at runtime. */
object TrevorAiArchitecture {
    fun decide(mode: TrevorMode, advanced: Boolean, fresh: Boolean, attachment: Boolean): TrevorAiDecision =
        TrevorAiDecision(
            model = GeminiAiProvider.MODEL,
            useSearch = fresh || mode == TrevorMode.RESEARCH,
            reason = when {
                fresh -> "fresh information requested"
                attachment -> "multimodal input"
                advanced -> "complex task"
                else -> "normal task"
            }
        )

    fun safeProvider(context: Context, requested: String): String {
        val s = TrevorSettingsStore.load(context)
        return if (s.geminiEnabled && s.aiEnabled) "GEMINI" else "LOCAL"
    }
}
