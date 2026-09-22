package com.trevor.assistant

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Local-only behaviour learning foundation.
 *
 * Part 1 learns coarse app transitions, sessions and time-of-day/day-type patterns
 * from UsageStats events. It stores only compact derived patterns, never raw event
 * history. No pattern is converted into an automation by this component.
 */
object TrevorBehaviorLearning {
    private const val PREFS = "trevor_behavior_learning"
    private const val TRANSITIONS = "app_transitions"
    private const val SESSIONS = "app_sessions"
    private const val ROUTINES = "routine_candidates"
    private const val LAST_SYNC = "last_usage_sync"
    private const val MAX_TRANSITIONS = 200
    private const val MAX_SESSIONS = 120
    private const val MAX_ROUTINES = 80
    private const val RETENTION_DAYS = 45

    data class TransitionPattern(
        val fromPackage: String,
        val toPackage: String,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    )

    data class SessionPattern(
        val packageName: String,
        val hourBucket: Int,
        val weekday: Int,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    )

    data class RoutineCandidate(
        val sequence: List<String>,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double,
        val approved: Boolean = false
    )

    fun sync(context: Context) {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(LAST_SYNC, 0L)
        val start = if (previous > 0L) {
            max(previous - 5_000L, now - RETENTION_DAYS * 86_400_000L)
        } else {
            now - 6L * 60L * 60L * 1000L
        }

        val events = runCatching { manager.queryEvents(start, now) }.getOrNull() ?: return
        val sequence = ArrayList<Pair<String, Long>>(8)
        val transitions = readTransitions(prefs)
        val sessions = readSessions(prefs)

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName?.trim().orEmpty()
            if (pkg.isBlank() || pkg == context.packageName) continue

            val timestamp = event.timeStamp
            val previousApp = sequence.lastOrNull()?.first
            if (previousApp != null && previousApp != pkg) {
                val key = previousApp + "->" + pkg
                val old = transitions[key]
                val observations = (old?.observations ?: 0) + 1
                transitions[key] = TransitionPattern(
                    previousApp, pkg, observations, timestamp, confidence(observations, timestamp)
                )
            }

            val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
            val hourBucket = calendar.get(Calendar.HOUR_OF_DAY) / 2
            val weekday = calendar.get(Calendar.DAY_OF_WEEK)
            val sessionKey = pkg + "|" + hourBucket + "|" + weekday
            val oldSession = sessions[sessionKey]
            val observations = (oldSession?.observations ?: 0) + 1
            sessions[sessionKey] = SessionPattern(
                pkg, hourBucket, weekday, observations, timestamp, confidence(observations, timestamp)
            )

            sequence += pkg to timestamp
            if (sequence.size > 8) sequence.removeAt(0)
            discoverRoutine(prefs, sequence)
        }

        writeTransitions(prefs, pruneTransitions(transitions, now))
        writeSessions(prefs, pruneSessions(sessions, now))
        prefs.edit().putLong(LAST_SYNC, now).apply()
    }

    fun transitions(context: Context): List<TransitionPattern> =
        readTransitions(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values.sortedByDescending { it.confidence }

    fun sessions(context: Context): List<SessionPattern> =
        readSessions(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values.sortedByDescending { it.confidence }

    fun routineCandidates(context: Context): List<RoutineCandidate> =
        readRoutines(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .values.filterNot { it.approved }
            .sortedByDescending { it.confidence }

    fun approveRoutine(context: Context, sequence: List<String>): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val routines = readRoutines(prefs)
        val target = sequence.filter { it.isNotBlank() }
        if (target.size < 2) return false
        val key = target.joinToString(">")
        val current = routines[key] ?: return false
        routines[key] = current.copy(approved = true)
        writeRoutines(prefs, routines)
        return true
    }

    fun summary(context: Context): String {
        sync(context)
        val top = transitions(context).take(5)
        val candidates = routineCandidates(context).take(5)
        return buildString {
            append("Local behaviour learning active. ")
            append(top.size).append(" high-confidence app transitions sampled.")
            if (top.isNotEmpty()) {
                append("\nTransitions:\n")
                top.forEach {
                    append("• ").append(shortPackage(it.fromPackage))
                        .append(" → ").append(shortPackage(it.toPackage))
                        .append(" (").append(it.observations).append(" observations)\n")
                }
            }
            if (candidates.isNotEmpty()) {
                append("Routine candidates awaiting approval: ").append(candidates.size).append(".")
            }
        }.trim()
    }

    private fun discoverRoutine(
        prefs: android.content.SharedPreferences,
        sequence: List<Pair<String, Long>>
    ) {
        if (sequence.size < 3) return
        val normalized = sequence.map { it.first }.distinct()
        if (normalized.size < 2 || normalized.size > 5) return
        val key = normalized.joinToString(">")
        val routines = readRoutines(prefs)
        val old = routines[key]
        val observations = (old?.observations ?: 0) + 1
        if (observations < 3 && old == null) return
        val lastSeen = sequence.last().second
        routines[key] = RoutineCandidate(
            normalized, observations, lastSeen,
            confidence(observations, lastSeen), old?.approved == true
        )
        writeRoutines(prefs, pruneRoutines(routines, System.currentTimeMillis()))
    }

    private fun confidence(observations: Int, lastSeen: Long): Double {
        val ageDays = ((System.currentTimeMillis() - lastSeen).coerceAtLeast(0L) / 86_400_000L).toDouble()
        val recency = 1.0 / (1.0 + ageDays / 14.0)
        val repetition = min(1.0, observations / 10.0)
        return (0.25 + 0.75 * repetition) * recency
    }

    private fun shortPackage(pkg: String): String =
        pkg.substringAfterLast('.').ifBlank { pkg }.take(32)

    private fun pruneTransitions(input: Map<String, TransitionPattern>, now: Long): Map<String, TransitionPattern> =
        input.values.filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { it.confidence }.take(MAX_TRANSITIONS)
            .associateBy { it.fromPackage + "->" + it.toPackage }

    private fun pruneSessions(input: Map<String, SessionPattern>, now: Long): Map<String, SessionPattern> =
        input.values.filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { it.confidence }.take(MAX_SESSIONS)
            .associateBy { it.packageName + "|" + it.hourBucket + "|" + it.weekday }

    private fun pruneRoutines(input: Map<String, RoutineCandidate>, now: Long): Map<String, RoutineCandidate> =
        input.values.filter { now - it.lastSeen <= RETENTION_DAYS * 86_400_000L }
            .sortedByDescending { it.confidence }.take(MAX_ROUTINES)
            .associateBy { it.sequence.joinToString(">") }

    private fun readTransitions(prefs: android.content.SharedPreferences): MutableMap<String, TransitionPattern> {
        val array = runCatching { JSONArray(prefs.getString(TRANSITIONS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, TransitionPattern>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val from = o.optString("from")
            val to = o.optString("to")
            if (from.isBlank() || to.isBlank()) continue
            out[from + "->" + to] = TransitionPattern(
                from, to, o.optInt("observations"), o.optLong("lastSeen"), o.optDouble("confidence")
            )
        }
        return out
    }

    private fun writeTransitions(prefs: android.content.SharedPreferences, values: Map<String, TransitionPattern>) {
        val array = JSONArray()
        values.values.forEach {
            array.put(
                JSONObject()
                    .put("from", it.fromPackage)
                    .put("to", it.toPackage)
                    .put("observations", it.observations)
                    .put("lastSeen", it.lastSeen)
                    .put("confidence", it.confidence)
            )
        }
        prefs.edit().putString(TRANSITIONS, array.toString()).apply()
    }

    private fun readSessions(prefs: android.content.SharedPreferences): MutableMap<String, SessionPattern> {
        val array = runCatching { JSONArray(prefs.getString(SESSIONS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, SessionPattern>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val pkg = o.optString("package")
            if (pkg.isBlank()) continue
            val hour = o.optInt("hour")
            val weekday = o.optInt("weekday")
            out[pkg + "|" + hour + "|" + weekday] = SessionPattern(
                pkg, hour, weekday, o.optInt("observations"), o.optLong("lastSeen"), o.optDouble("confidence")
            )
        }
        return out
    }

    private fun writeSessions(prefs: android.content.SharedPreferences, values: Map<String, SessionPattern>) {
        val array = JSONArray()
        values.values.forEach {
            array.put(
                JSONObject()
                    .put("package", it.packageName)
                    .put("hour", it.hourBucket)
                    .put("weekday", it.weekday)
                    .put("observations", it.observations)
                    .put("lastSeen", it.lastSeen)
                    .put("confidence", it.confidence)
            )
        }
        prefs.edit().putString(SESSIONS, array.toString()).apply()
    }

    private fun readRoutines(prefs: android.content.SharedPreferences): MutableMap<String, RoutineCandidate> {
        val array = runCatching { JSONArray(prefs.getString(ROUTINES, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, RoutineCandidate>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val seq = o.optJSONArray("sequence") ?: continue
            val sequence = mutableListOf<String>()
            for (j in 0 until seq.length()) sequence += seq.optString(j)
            if (sequence.size < 2) continue
            out[sequence.joinToString(">")] = RoutineCandidate(
                sequence, o.optInt("observations"), o.optLong("lastSeen"),
                o.optDouble("confidence"), o.optBoolean("approved")
            )
        }
        return out
    }

    private fun writeRoutines(prefs: android.content.SharedPreferences, values: Map<String, RoutineCandidate>) {
        val array = JSONArray()
        values.values.forEach {
            val seq = JSONArray()
            it.sequence.forEach(seq::put)
            array.put(
                JSONObject()
                    .put("sequence", seq)
                    .put("observations", it.observations)
                    .put("lastSeen", it.lastSeen)
                    .put("confidence", it.confidence)
                    .put("approved", it.approved)
            )
        }
        prefs.edit().putString(ROUTINES, array.toString()).apply()
    }
}
