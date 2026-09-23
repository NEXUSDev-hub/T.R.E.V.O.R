package com.trevor.assistant

import java.util.Locale

/**
 * Part 4: deterministic intent/context layer used before provider selection.
 * It classifies requests only; it never executes actions or grants permissions.
 */
object TrevorSmartCore {
    enum class IntentKind {
        QUESTION, EXPLANATION, RESEARCH, ANALYSIS, PROJECT,
        AUTOMATION, TERMINAL, MEMORY, REMINDER, GENERAL
    }

    data class Intent(
        val kind: IntentKind,
        val confidence: Double,
        val needsFreshInformation: Boolean,
        val needsAdvancedModel: Boolean,
        val evidence: List<String> = emptyList()
    )

    private data class Candidate(
        val kind: IntentKind,
        val score: Int,
        val evidence: List<String>
    )

    fun classify(input: String, mode: TrevorMode, attachmentPresent: Boolean = false): Intent {
        val text = input.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) {
            return Intent(IntentKind.GENERAL, 1.0, false, attachmentPresent, listOf("blank-input"))
        }

        val explicit = when (mode) {
            TrevorMode.TERMINAL -> Candidate(IntentKind.TERMINAL, 100, listOf("explicit-terminal-mode"))
            TrevorMode.RESEARCH -> Candidate(IntentKind.RESEARCH, 100, listOf("explicit-research-mode"))
            TrevorMode.ANALYSE -> Candidate(IntentKind.ANALYSIS, 100, listOf("explicit-analysis-mode"))
            TrevorMode.PROJECT -> Candidate(IntentKind.PROJECT, 100, listOf("explicit-project-mode"))
            else -> null
        }

        val candidates = if (explicit != null) {
            listOf(explicit)
        } else {
            listOf(
                scoreMemory(text), scoreReminder(text), scoreTerminal(text),
                scoreResearch(text), scoreAnalysis(text), scoreProject(text),
                scoreAutomation(text), scoreExplanation(text), scoreQuestion(text),
                Candidate(IntentKind.GENERAL, 1, listOf("no-special-intent"))
            )
        }

        val ranked = candidates.sortedWith(
            compareByDescending<Candidate> { it.score }.thenByDescending { it.evidence.size }
        )
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.score ?: 0
        val margin = (best.score - second).coerceAtLeast(0)

        val confidence = when {
            best.score >= 100 -> 0.99
            best.score >= 9 && margin >= 4 -> 0.94
            best.score >= 7 && margin >= 3 -> 0.88
            best.score >= 4 && margin >= 2 -> 0.78
            best.score >= 2 -> 0.64
            else -> 0.50
        }

        val fresh = best.kind == IntentKind.RESEARCH || containsAny(
            text, "latest", "today", "current", "right now", "recent",
            "this week", "this month", "news", "verify", "look up"
        )

        val advanced = attachmentPresent ||
            best.kind == IntentKind.RESEARCH ||
            best.kind == IntentKind.ANALYSIS ||
            best.kind == IntentKind.PROJECT ||
            best.kind == IntentKind.TERMINAL ||
            (best.kind == IntentKind.AUTOMATION && isMultiStep(text)) ||
            input.length > 1800

        return Intent(best.kind, confidence, fresh, advanced, best.evidence.take(4))
    }

    fun shouldUseAdvanced(input: String, mode: TrevorMode, attachmentPresent: Boolean = false): Boolean =
        classify(input, mode, attachmentPresent).needsAdvancedModel

    fun instruction(input: String, mode: TrevorMode, attachmentPresent: Boolean = false): String {
        val intent = classify(input, mode, attachmentPresent)
        return buildString {
            append("Intent=").append(intent.kind.name)
            append("; confidence=").append("%.2f".format(Locale.ROOT, intent.confidence))
            append("; freshInformation=").append(intent.needsFreshInformation)
            append("; advancedReasoning=").append(intent.needsAdvancedModel)
            if (intent.evidence.isNotEmpty()) append("; evidence=").append(intent.evidence.joinToString(","))
            append(".")
        }
    }

    private fun scoreMemory(text: String) = if (Regex("""^(remember|save|store)\b""").containsMatchIn(text))
        Candidate(IntentKind.MEMORY, 12, listOf("memory-command")) else Candidate(IntentKind.MEMORY, 0, emptyList())

    private fun scoreReminder(text: String) = if (Regex("""^remind me\b""").containsMatchIn(text))
        Candidate(IntentKind.REMINDER, 12, listOf("reminder-command")) else Candidate(IntentKind.REMINDER, 0, emptyList())

    private fun scoreTerminal(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        if (Regex("""^(terminal|shell|command line)\b""").containsMatchIn(text)) {
            score += 10; evidence += "terminal-prefix"
        }
        if (containsAny(text, "run in terminal", "execute in terminal", "shell command")) {
            score += 8; evidence += "terminal-request"
        }
        return Candidate(IntentKind.TERMINAL, score, evidence)
    }

    private fun scoreResearch(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        listOf("research", "deep dive", "investigate", "look up", "verify", "sources", "evidence").forEach {
            if (text.contains(it)) { score += 4; evidence += it.replace(' ', '-') }
        }
        if (containsAny(text, "latest", "today", "current", "recent", "news", "this week")) {
            score += 5; evidence += "freshness"
        }
        return Candidate(IntentKind.RESEARCH, score, evidence)
    }

    private fun scoreAnalysis(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        listOf("analyse", "analyze", "analysis", "audit", "debug", "inspect", "diagnose", "compare", "review").forEach {
            if (text.contains(it)) { score += 3; evidence += it }
        }
        if (Regex("""\b(find|identify)\b.{0,30}\b(error|bug|issue|problem)\b""").containsMatchIn(text)) {
            score += 4; evidence += "error-investigation"
        }
        return Candidate(IntentKind.ANALYSIS, score, evidence)
    }

    private fun scoreProject(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        listOf("project", "architecture", "roadmap", "prototype").forEach {
            if (hasTerm(text, it)) { score += 3; evidence += it }
        }
        if (hasTerm(text, "implement", "develop")) {
            score += if (hasTerm(text, "project", "architecture", "roadmap", "prototype")) 3 else 1
            evidence += "development"
        }
        return Candidate(IntentKind.PROJECT, score, evidence)
    }

    private fun scoreAutomation(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        val actionWords = listOf("open", "launch", "start", "turn on", "turn off", "enable", "disable", "set", "share", "copy")
        val questionLike = Regex("""^(what|why|how|when|where|who|which|can|could|is|are|does|do)\b""").containsMatchIn(text)
        actionWords.forEach {
            val pattern = Regex("""\b""" + Regex.escape(it) + """\b""")
            if (pattern.containsMatchIn(text)) { score += 3; evidence += it.replace(' ', '-') }
        }
        if (questionLike) score = (score - 4).coerceAtLeast(0)
        if (score > 0 && !hasActionTarget(text)) score = (score - 2).coerceAtLeast(0)
        return Candidate(IntentKind.AUTOMATION, score, evidence)
    }

    private fun scoreExplanation(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        listOf("explain", "define", "meaning", "difference", "teach me").forEach {
            if (text.contains(it)) { score += 4; evidence += it.replace(' ', '-') }
        }
        return Candidate(IntentKind.EXPLANATION, score, evidence)
    }

    private fun scoreQuestion(text: String): Candidate {
        var score = 0
        val evidence = mutableListOf<String>()
        if (Regex("""^(what|why|how|when|where|who|which|can|could|is|are|does|do)\b""").containsMatchIn(text)) {
            score += 5; evidence += "question-prefix"
        }
        if (text.endsWith("?")) { score += 2; evidence += "question-mark" }
        return Candidate(IntentKind.QUESTION, score, evidence)
    }

    private fun isMultiStep(text: String): Boolean =
        Regex("""\b(then|after that|next|finally|step)\b""").findAll(text).count() >= 2

    private fun hasActionTarget(text: String): Boolean =
        Regex("""\b(on|off|wifi|bluetooth|display|sound|battery|notifications|settings|app|application|text|clipboard)\b""")
            .containsMatchIn(text) || text.split(Regex("""\s+""")).size >= 3

    private fun hasTerm(text: String, vararg terms: String): Boolean =
        terms.any { term ->
            if (term.contains(' ')) text.contains(term)
            else java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(term) + "\\b")
                .matcher(text).find()
        }

    private fun containsAny(text: String, vararg values: String): Boolean =
        values.any { value -> if (value.contains(' ')) text.contains(value) else hasTerm(text, value) }
