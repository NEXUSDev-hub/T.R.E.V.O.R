package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.delay
import java.util.Locale

object TrevorOfflineUiAgent {
    private const val MAX_STEPS = 12
    private const val WAIT_MS = 400L

    suspend fun pursueGoal(context: Context, goal: String, expectedPackage: String? = null, fast: Boolean = false): Result<String> {
        val service = TrevorAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Enable TREVOR Accessibility Service first."))
        val pkg = expectedPackage?.takeIf { it.isNotBlank() } ?: inferPackage(goal)
        val terms = targetTerms(goal)

        if (fast && pkg != null && service.currentPackage() != pkg) {
            launchPackage(context, pkg)
            delay(WAIT_MS)
        }

        if (pkg != null) {
            val learned = TrevorUiLearning.find(context, pkg, goal).firstOrNull()
            if (learned != null && replayLearned(service, learned.steps)) {
                TrevorUiLearning.record(context, pkg, goal, learned.steps, true)
                return Result.success("Completed using a learned UI workflow.")
            }
        }

        repeat(MAX_STEPS) {
            val observation = TrevorOfflineVision.observe(service).getOrNull()
            if (observation == null) {
                delay(WAIT_MS)
                return@repeat
            }

            val target = chooseTarget(observation, terms)
            if (target != null && target.clickable &&
                service.clickAt(target.bounds.centerX(), target.bounds.centerY())) {
                delay(WAIT_MS)
                val after = TrevorOfflineVision.observe(service).getOrNull()
                if (after != null && screenSignature(after) != screenSignature(observation)) {
                    TrevorOfflineLearning.observe(
                        context, "UI goal: $goal", observation.packageName,
                        "UI_AGENT_CLICK", TrevorOfflineLearning.Outcome.SUCCESS,
                        "Clicked " + target.text.ifBlank { target.description }
                    )
                    if (goalLooksCompleted(goal, after)) return Result.success("Completed and verified locally.")
                }
            }

            val w = observation.width.coerceAtLeast(1)
            val h = observation.height.coerceAtLeast(1)
            if (service.swipe(w / 2, (h * 0.78f).toInt(), w / 2, (h * 0.28f).toInt())) {
                delay(WAIT_MS)
            }
        }

        TrevorOfflineLearning.observe(
            context, "UI goal: $goal", pkg ?: "unknown", "UI_AGENT",
            TrevorOfflineLearning.Outcome.FAILURE,
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

    private fun chooseTarget(observation: TrevorScreenObservation, terms: List<String>): TrevorVisualElement? {
        var best: TrevorVisualElement? = null
        var bestScore = 0
        for (element in observation.elements) {
            if (element.bounds.width() <= 0 || element.bounds.height() <= 0) continue
            val haystack = (element.text + " " + element.description).lowercase(Locale.ROOT)
            var score = 0
            for (term in terms) {
                score += when {
                    haystack == term -> 100
                    haystack.contains(term) -> 45
                    else -> 0
                }
            }
            if (element.clickable) score += 10
            if (score > bestScore) {
                bestScore = score
                best = element
            }
        }
        return best
    }

    private fun targetTerms(goal: String): List<String> {
        val stop = setOf("open","go","to","the","a","an","and","then","find","show","please","my","in","on","app","tab","screen")
        return goal.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in stop }.distinct().take(10)
    }

    private fun goalLooksCompleted(goal: String, observation: TrevorScreenObservation): Boolean {
        val terms = targetTerms(goal)
        if (terms.isEmpty()) return true
        var matched = 0
        for (term in terms) {
            if (observation.elements.any { it.text.contains(term, true) || it.description.contains(term, true) }) matched++
        }
        return matched >= (terms.size + 1) / 2
    }

    private fun screenSignature(observation: TrevorScreenObservation): String =
        (observation.packageName + "|" + observation.elements.take(80).joinToString("|") {
            it.text + ":" + it.description + ":" + it.bounds.toShortString()
        }).hashCode().toString()

    private fun launchPackage(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
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
