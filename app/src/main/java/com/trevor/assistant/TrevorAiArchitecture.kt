package com.trevor.assistant

import android.content.Context

data class TrevorAiDecision(
    val model: String,
    val useSearch: Boolean,
    val reason: String
)

/** Part 16: one authority for model selection. */
object TrevorAiArchitecture {
    fun decide(mode: TrevorMode, advanced: Boolean, fresh: Boolean, attachment: Boolean): TrevorAiDecision {
        val useAdvanced = advanced || fresh || attachment || mode == TrevorMode.ANALYSE ||
            mode == TrevorMode.RESEARCH || mode == TrevorMode.PROJECT || mode == TrevorMode.TERMINAL
        return TrevorAiDecision(
            if (useAdvanced) GeminiAiProvider.ADVANCED_MODEL else GeminiAiProvider.NORMAL_MODEL,
            fresh || mode == TrevorMode.RESEARCH,
            if (fresh) "fresh information requested" else if (attachment) "multimodal input" else if (useAdvanced) "complex task" else "normal task"
        )
    }

    fun safeProvider(context: Context, requested: String): String {
        val s = TrevorSettingsStore.load(context)
        return if (s.geminiEnabled && s.aiEnabled) requested else "LOCAL"
    }
}
