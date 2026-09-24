package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Bounded offline UI agent.
 * SEE -> CHOOSE -> ACT -> SEE -> VERIFY -> RECOVER.
 */
object TrevorOfflineUiAgent {
    private const val MAX_STEPS = 12
    private const val WAIT_MS = 400L

    suspend fun pursueGoal(context: Context, goal: String, expectedPackage: String? = null, fast: Boolean = false): Result<String> {
        val service = TrevorAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Enable TREVOR Accessibility Service first."))
        val pkg = expectedPackage?.takeIf { it.isNotBlank() } ?: inferPackage(goal)
        val terms = targetTerms(goal)
        if (fast && pkg != null && service.currentPackage() != pkg) launchPackage(context, pkg)

        if (pkg != null) {
            val learned = TrevorUiLearning.find(context, pkg, goal).firstOrNull()
            if (learned != null && replayLearned(service, learned.steps)) {
                TrevorUiLearning.record(context, pkg, goal, learned.steps, true)
                return Result.success("Completed using a learned UI workflow.")
            }
        }

        repeat(MAX_STEPS) {
            val observation = TrevorOfflineVision.observe(service).getOrNull()
            if (observation != null) {
                val target = chooseTarget(observation, terms)
                if (target != null && service.clickAt(target.bounds.centerX(), target.bounds.centerY())) {
                    delay(WAIT_MS)
                    val after = TrevorOfflineVision.observe(service).getOrNull()
                    if (after != null && screenSignature(after) != screenSignature(observation)) {
                        TrevorOfflineLearning.observe(
                            context, "UI goal: $goal", observation.packageName,
                            "UI_AGENT_CLICK", TrevorOfflineLearning.Outcome.SUCCESS,
                            "Clicked ${target.text.ifBlank { target.description }}"
                        )
                        if (goalLooksCompleted(goal, after)) return Result.success("Completed and verified locally.")
                    }
                }
                val direction = scrollDirection(goal)
                val w = observation.width
                val h = observation.height
                val swiped = if (direction > 0)
                    service.swipe(w / 2, (h * .78f).toInt(), w / 2, (h * .28f).toInt())
                else
                    service.swipe(w / 2, (h * .28f).toInt(), w / 2, (h * .78f).toInt())
                if (swiped) delay(WAIT_MS)
            } else {
                delay(WAIT_MS)
            }
        }
        TrevorOfflineLearning.observe(
            context, "UI goal: $goal", pkg ?: "unknown",
            "UI_AGENT", TrevorOfflineLearning.Outcome.FAILURE,
            "Offline UI agent exhausted its bounded interaction budget."
        )
        return Result.failure(IllegalStateException("Could not complete the UI goal offline within $MAX_STEPS interaction steps."))
    }

    private suspend fun replayLearned(service: TrevorAccessibilityService, steps: List<TrevorUiLearning.UiStep>): Boolean {
        for (step in steps.take(MAX_STEPS)) {
            val ok = when {
                step.targetText.isNotBlank() -> service.clickText(step.targetText)
                step.contentDescription.isNotBlank() -> service.clickDescription(step.contentDescription)
                step.x >= 0 && step.y >= 0 -> service.clickAt(step.x, step.y)
                else -> false
            }
            if (!ok) return false
            delay(WAIT_MS)
        }
        return true
    }

    private fun chooseTarget(o: TrevorScreenObservation, terms: List<String>): TrevorVisualElement? =
        o.elements.asSequence()
            .filter { it.bounds.width() > 0 && it.bounds.height() > 0 }
            .map { e ->
                val hay = (e.text + " " + e.description).lowercase(Locale.ROOT)
                val score = terms.sumOf { term ->
                    when {
                        hay == term -> 100
                        hay.contains(term) -> 45
                        else -> 0
                    }
                } + if (e.clickable) 10 else 0
                e to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first

    private fun targetTerms(goal: String): List<String> {
        val stop = setOf("open","go","to","the","a","an","and","then","find","show","please","my","in","on","app","tab","screen")
        return goal.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in stop }.distinct().take(10)
    }

    private fun goalLooksCompleted(goal: String, o: TrevorScreenObservation): Boolean {
        val terms = targetTerms(goal)
        return terms.isEmpty() || terms.count { t -> o.elements.any { it.text.contains(t,true) || it.description.contains(t,true) } } >= ((terms.size + 1) / 2)
    }

    private fun screenSignature(o: TrevorScreenObservation): String =
        (o.packageName + "|" + o.elements.take(80).joinToString("|") {
            it.text + ":" + it.description + ":" + it.bounds.toShortString()
        }).hashCode().toString()

    private fun scrollDirection(goal: String): Int =
        if (goal.contains("up", true) || goal.contains("above", true)) -1 else 1

    private fun launchPackage(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun inferPackage(goal: String): String? = when {
        goal.contains("youtube", true) -> "com.google.android.youtube"
        goal.contains("chrome", true) -> "com.android.chrome"
        goal.contains("settings", true) -> "com.android.settings"
        else -> null
    }
}
