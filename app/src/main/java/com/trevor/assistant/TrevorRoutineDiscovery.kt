package com.trevor.assistant

import android.content.Context
import java.util.Locale

data class TrevorRoutineSuggestion(
    val sequence: List<String>,
    val confidence: Double,
    val observations: Int,
    val contextMatch: Double,
    val trigger: String,
    val distinctDays: Int = 0,
    val distinctSessions: Int = 0
)

object TrevorRoutineDiscovery {
    private const val MIN_CONFIDENCE = 0.35
    private const val MIN_OBSERVATIONS = 3
    private const val MIN_DISTINCT_DAYS = 2
    private const val MIN_DISTINCT_SESSIONS = 2
    private const val MAX_SUGGESTIONS = 6
    private const val MAX_CANDIDATE_AGE_DAYS = 45L

    /**
     * Part 3: turns the local behaviour/context signals from Parts 1-2 into
     * ranked routine candidates. This never enables an automation by itself.
     */
    fun suggestions(context: Context): List<TrevorRoutineSuggestion> {
        val settings = TrevorSettingsStore.load(context)
        if (!settings.usageIntelligenceEnabled || !TrevorBehaviorLearning.hasUsageAccess(context)) {
            return emptyList()
        }

        // Refresh local signals when discovery is explicitly requested instead of
        // waiting for the next periodic background sample.
        TrevorBehaviorLearning.sync(context)
        TrevorDeviceContextLearning.recordCurrentForegroundContext(context)

        val current = TrevorDeviceContextLearning.snapshot(context)
        val contextPatterns = TrevorDeviceContextLearning.observations(context)
        val appTransitions = TrevorBehaviorLearning.transitions(context)
            .associateBy { it.fromPackage + "->" + it.toPackage }

        val now = System.currentTimeMillis()
        return TrevorBehaviorLearning.routineCandidates(context)
            .filter { it.observations >= MIN_OBSERVATIONS }
            .filter { it.distinctDays >= MIN_DISTINCT_DAYS && it.distinctSessions >= MIN_DISTINCT_SESSIONS }
            .filter { now - it.lastSeen <= MAX_CANDIDATE_AGE_DAYS * 86_400_000L }
            .map { candidate ->
                val triggerPackage = candidate.sequence.firstOrNull()
                val contextMatch = contextPatterns
                    .filter { it.packageName == triggerPackage }
                    .maxOfOrNull { pattern -> contextSimilarity(current, pattern) }
                    ?: 0.0

                val appTransitionSupport = candidate.sequence
                    .zipWithNext()
                    .map { (from, to) ->
                        appTransitions[from + "->" + to]?.confidence ?: 0.0
                    }
                    .average()
                    .coerceIn(0.0, 1.0)

                // Repeated behaviour remains the strongest signal. Richer device
                // context contributes without allowing a one-off context to create
                // an automation candidate.
                val blended =
                    (candidate.confidence * 0.60) +
                        (appTransitionSupport * 0.25) +
                        (contextMatch * 0.15)

                TrevorRoutineSuggestion(
                    sequence = candidate.sequence,
                    confidence = blended.coerceIn(0.0, 1.0),
                    observations = candidate.observations,
                    contextMatch = contextMatch,
                    trigger = triggerFor(current, contextMatch),
                    distinctDays = candidate.distinctDays,
                    distinctSessions = candidate.distinctSessions
                )
            }
            .filter {
                it.confidence >= MIN_CONFIDENCE &&
                    it.observations >= MIN_OBSERVATIONS &&
                    it.distinctDays >= MIN_DISTINCT_DAYS &&
                    it.distinctSessions >= MIN_DISTINCT_SESSIONS
            }
            .sortedWith(
                compareByDescending<TrevorRoutineSuggestion> { it.confidence }
                    .thenByDescending { it.observations }
                    .thenByDescending { it.sequence.size }
            )
            .fold(mutableListOf<TrevorRoutineSuggestion>()) { selected, item ->
                val coveredByStronger = selected.any { stronger ->
                    stronger.sequence.size > item.sequence.size &&
                        stronger.sequence.take(item.sequence.size) == item.sequence &&
                        stronger.confidence >= item.confidence
                }
                if (!coveredByStronger && selected.size < MAX_SUGGESTIONS) {
                    selected.add(item)
                }
                selected
            }
    }

    fun summary(context: Context): String {
        val items = suggestions(context)
        if (items.isEmpty()) {
            return "No routine candidate has enough repeated local evidence yet."
        }
        return buildString {
            append("Routine candidates awaiting approval:\n")
            items.forEachIndexed { index, item ->
                append(index + 1)
                    .append(". ")
                    .append(item.sequence.joinToString(" → ") { displayPackage(context, it) })
                    .append(" — ")
                    .append((item.confidence * 100).toInt())
                    .append("% confidence, ")
                    .append(item.observations)
                    .append(" observations")
                    .append(" (")
                    .append(item.trigger)
                    .append(")\n")
            }
            append("No candidate is automatically enabled.")
        }.trim()
    }

    fun approve(context: Context, sequence: List<String>): Boolean =
        TrevorBehaviorLearning.approveRoutine(context, sequence)

    fun revoke(context: Context, sequence: List<String>): Boolean =
        TrevorBehaviorLearning.revokeRoutineApproval(context, sequence)

    private fun contextSimilarity(
        current: TrevorDeviceContextLearning.ContextSnapshot,
        learned: TrevorDeviceContextLearning.ContextObservation
    ): Double {
        var score = 0.0
        var weight = 0.0

        fun add(matches: Boolean, amount: Double) {
            weight += amount
            if (matches) score += amount
        }

        add(current.hourBucket == learned.hourBucket, 1.0)
        add(current.weekday == learned.weekday, 0.7)
        add(current.charging == learned.charging, 0.8)
        add(
            current.batteryPercent < 0 ||
                learned.batteryBucket < 0 ||
                current.batteryPercent / 5 * 5 == learned.batteryBucket,
            0.6
        )
        add(current.network == learned.network, 0.7)
        add(current.networkTransports == learned.networkTransports, 0.6)
        add(current.networkValidated == learned.networkValidated, 0.5)
        add(current.metered == learned.metered, 0.5)
        add(current.temporaryUnmetered == learned.temporaryUnmetered, 0.4)
        add(
            current.downstreamBandwidthBucket == learned.downstreamBandwidthBucket,
            0.35
        )
        add(current.screenInteractive == learned.screenInteractive, 0.5)
        if (current.bluetoothEnabled != null && learned.bluetoothEnabled != null) {
            add(current.bluetoothEnabled == learned.bluetoothEnabled, 0.3)
        }
        add(current.orientation == learned.orientation, 0.3)

        return if (weight == 0.0) 0.0 else score / weight
    }

    private fun triggerFor(
        current: TrevorDeviceContextLearning.ContextSnapshot,
        contextMatch: Double
    ): String {
        val time = String.format(
            Locale.ROOT,
            "%02d:00–%02d:00",
            current.hourBucket * 2,
            current.hourBucket * 2 + 2
        )
        return if (contextMatch >= 0.7) {
            "strong current-context match around $time"
        } else {
            "repeated pattern around $time"
        }
    }

    private fun displayPackage(context: Context, packageName: String): String =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(context.packageManager)
                .toString()
                .trim()
                .take(28)
        }.getOrDefault("an app")
}
