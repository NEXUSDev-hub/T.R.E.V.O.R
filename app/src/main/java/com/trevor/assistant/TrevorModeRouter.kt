package com.trevor.assistant

object TrevorModeRouter {
    fun route(mode: TrevorMode?, command: String): TrevorMode {
        if (mode != null) return mode
        val text = command.trim().lowercase()
        if (text.isBlank()) return TrevorMode.NORMAL
        if (containsAny(text, "terminal", "command line")) return TrevorMode.TERMINAL
        if (containsAny(text, "ratio shifter", "adaptive layout", "responsive layout", "landscape layout", "portrait layout")) return TrevorMode.RATIO_SHIFTER
        val scores = mapOf(
            TrevorMode.PROJECT to score(text, "project", "prototype", "architecture", "roadmap", "build this", "develop this"),
            TrevorMode.RESEARCH to score(text, "research", "sources", "evidence", "latest", "look up", "investigate", "deep dive"),
            TrevorMode.ANALYSE to score(text, "analyse", "analyze", "analysis", "inspect", "review", "diagnose", "find what's wrong")
        )
        val best = scores.maxByOrNull { it.value }
        return if (best != null && best.value > 0) best.key else TrevorMode.NORMAL
    }

    private fun score(text: String, vararg phrases: String): Int =
        phrases.count { phrase -> text.contains(phrase) }

    private fun containsAny(text: String, vararg phrases: String): Boolean =
        phrases.any { phrase -> text.contains(phrase) }
}
