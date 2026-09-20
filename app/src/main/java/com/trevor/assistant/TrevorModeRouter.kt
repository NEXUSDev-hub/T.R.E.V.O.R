package com.trevor.assistant

/**
 * Resolves TREVOR's operating mode from natural human language.
 *
 * Users do not need to issue rigid commands such as "project Mars".
 * TREVOR looks for conversational intent and falls back to NORMAL.
 */
object TrevorModeRouter {

    fun route(mode: TrevorMode?, command: String): TrevorMode {
        if (mode != null) return mode

        val normalized = command
            .trim()
            .lowercase()

        if (normalized.isBlank()) {
            return TrevorMode.NORMAL
        }

        if (looksLikeRatioShifter(normalized)) {
            return TrevorMode.RATIO_SHIFTER
        }

        val scores = mapOf(
            TrevorMode.PROJECT to projectScore(normalized),
            TrevorMode.RESEARCH to researchScore(normalized),
            TrevorMode.ANALYSE to analyseScore(normalized)
        )

        val best = scores.maxByOrNull { it.value }

        return if (best != null && best.value > 0) {
            best.key
        } else {
            TrevorMode.NORMAL
        }
    }

    private fun projectScore(text: String): Int {
        var score = 0

        if (containsAny(text,
                "starting a project",
                "start a project",
                "initiating a project",
                "initiate a project",
                "working on a project",
                "beginning a project",
                "building a project",
                "developing a project",
                "my project",
                "this project",
                "project idea"
            )) score += 5

        if (containsAny(text,
                "build this with you",
                "develop this with you",
                "let's build",
                "lets build",
                "let's develop",
                "lets develop",
                "i want to build",
                "i want to develop",
                "i want to make"
            )) score += 3

        if (containsAny(text,
                "project",
                "prototype",
                "architecture",
                "roadmap"
            )) score += 1

        return score
    }

    private fun researchScore(text: String): Int {
        var score = 0

        if (containsAny(text,
                "researching",
                "research on",
                "research into",
                "research about",
                "doing research",
                "conducting research",
                "investigating",
                "investigate",
                "looking into",
                "dig into",
                "deep dive"
            )) score += 5

        if (containsAny(text,
                "find out what",
                "find out why",
                "find out how",
                "what do the sources say",
                "latest information",
                "look up the latest",
                "compare the sources"
            )) score += 3

        if (containsAny(text,
                "research",
                "sources",
                "evidence",
                "papers"
            )) score += 1

        return score
    }

    private fun analyseScore(text: String): Int {
        var score = 0

        if (containsAny(text,
                "analyse this",
                "analyze this",
                "can you analyse",
                "can you analyze",
                "please analyse",
                "please analyze",
                "just finished this",
                "i just finished",
                "check what i got",
                "check my result",
                "look over this",
                "tell me what's wrong",
                "tell me what is wrong",
                "find what's wrong",
                "find what is wrong",
                "break this down"
            )) score += 5

        if (containsAny(text,
                "analyse",
                "analyze",
                "analysis",
                "evaluate",
                "inspect",
                "review",
                "diagnose",
                "interpret"
            )) score += 2

        return score
    }

    private fun looksLikeRatioShifter(text: String): Boolean {
        return containsAny(
            text,
            "ratio shifter",
            "adaptive layout",
            "adapt the layout",
            "change the layout",
            "responsive layout",
            "switch to landscape layout",
            "switch to portrait layout"
        )
    }

    private fun containsAny(
        text: String,
        vararg phrases: String
    ): Boolean {
        return phrases.any { text.contains(it) }
    }
}
