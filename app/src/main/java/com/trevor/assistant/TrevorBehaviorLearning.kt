package com.trevor.assistant

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Part 1: local-only deep behaviour learning.
 *
 * Learns app transitions, usage-session boundaries, time/day patterns, repeated
 * ordered sequences, confidence/recency/decay, and approval-gated routine candidates.
 *
 * This component never executes an automation.
 */
object TrevorBehaviorLearning {
    private const val PREFS = "trevor_behavior_learning"
    private const val TRANSITIONS = "app_transitions"
    private const val SESSIONS = "app_sessions"
    private const val ROUTINES = "routine_candidates"
    private const val LAST_SYNC = "last_usage_sync"
    private const val LAST_EVENT_TIME = "last_event_time"
    private const val LAST_EVENT_PACKAGE = "last_event_package"
    private const val LAST_SEQUENCE = "last_sequence"

    private const val MAX_TRANSITIONS = 200
    private const val MAX_SESSIONS = 160
    private const val MAX_ROUTINES = 120
    private const val MAX_SEQUENCE_LENGTH = 6
    private const val SESSION_GAP_MS = 30L * 60L * 1000L
    private const val RETENTION_DAYS = 45L
    private const val ROUTINE_MIN_OBSERVATIONS = 3
    private const val WORK_NAME = "trevor_behavior_learning"

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

    data class SyncResult(
        val processedEvents: Int,
        val transitionsLearned: Int,
        val sessionsLearned: Int,
        val routinesUpdated: Int,
        val usageAccessGranted: Boolean
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun sync(context: Context): SyncResult {
        if (!hasUsageAccess(context)) return SyncResult(0, 0, 0, 0, false)

        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return SyncResult(0, 0, 0, 0, true)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val storedCursor = prefs.getLong(LAST_EVENT_TIME, 0L)
        val previousSync = prefs.getLong(LAST_SYNC, 0L)
        val retentionStart = now - TimeUnit.DAYS.toMillis(RETENTION_DAYS)

        val start = when {
            storedCursor > 0L -> max(storedCursor - 1_000L, retentionStart)
            previousSync > 0L -> max(previousSync - 1_000L, retentionStart)
            else -> now - TimeUnit.HOURS.toMillis(6)
        }

        val events = runCatching { manager.queryEvents(start, now) }.getOrNull()
            ?: return SyncResult(0, 0, 0, 0, true)

        val transitions = readTransitions(prefs)
        val sessions = readSessions(prefs)
        val routines = readRoutines(prefs)

        var lastEventTime = storedCursor
        var lastEventPackage = prefs.getString(LAST_EVENT_PACKAGE, "").orEmpty()
        var processed = 0
        var transitionUpdates = 0
        var sessionUpdates = 0
        var routineUpdates = 0

        val sequence = readSequence(prefs)
        var lastTimestamp = sequence.lastOrNull()?.second ?: 0L
        var lastPackage = sequence.lastOrNull()?.first.orEmpty()
        if (lastTimestamp > 0L && now - lastTimestamp > SESSION_GAP_MS) {
            sequence.clear()
            lastTimestamp = 0L
            lastPackage = ""
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue

            val pkg = event.packageName?.trim().orEmpty()
            if (pkg.isBlank() || pkg == context.packageName) continue

            val timestamp = event.timeStamp
            val boundaryDuplicate =
                timestamp < storedCursor ||
                    (timestamp == storedCursor &&
                        pkg == prefs.getString(LAST_EVENT_PACKAGE, "").orEmpty())
            if (boundaryDuplicate) continue

            // A long gap starts a new usage session and prevents a sequence from
            // accidentally joining two unrelated periods of phone use.
            if (lastTimestamp > 0L && timestamp - lastTimestamp > SESSION_GAP_MS) {
                sequence.clear()
                lastPackage = ""
            }

            if (lastPackage.isNotBlank() && lastPackage != pkg) {
                val key = transitionKey(lastPackage, pkg)
                val old = transitions[key]
                val observations = (old?.observations ?: 0) + 1
                transitions[key] = TransitionPattern(
                    lastPackage,
                    pkg,
                    observations,
                    timestamp,
                    confidence(observations, timestamp)
                )
                transitionUpdates++
            }

            val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
            val hourBucket = calendar.get(Calendar.HOUR_OF_DAY) / 2
            val weekday = calendar.get(Calendar.DAY_OF_WEEK)
            val sessionKey = sessionKey(pkg, hourBucket, weekday)
            val oldSession = sessions[sessionKey]
            val sessionObservations = (oldSession?.observations ?: 0) + 1
            sessions[sessionKey] = SessionPattern(
                pkg,
                hourBucket,
                weekday,
                sessionObservations,
                timestamp,
                confidence(sessionObservations, timestamp)
            )
            sessionUpdates++

            // Collapse only consecutive duplicate app resumes. A -> B -> A stays A -> B -> A.
            if (sequence.isEmpty() || sequence.last().first != pkg) {
                sequence += pkg to timestamp
            } else {
                sequence[sequence.lastIndex] = pkg to timestamp
            }
            while (sequence.size > MAX_SEQUENCE_LENGTH) sequence.removeAt(0)

            // Part 3 discovery counts only newly completed suffixes. The previous
            // implementation re-counted every older subsequence on every event,
            // inflating observations for long-running sessions.
            routineUpdates += discoverRoutines(routines, sequence, timestamp)

            lastTimestamp = timestamp
            lastPackage = pkg
            lastEventTime = timestamp
            lastEventPackage = pkg
            processed++
        }

        writeTransitions(prefs, pruneTransitions(transitions, now))
        writeSessions(prefs, pruneSessions(sessions, now))
        writeRoutines(prefs, pruneRoutines(routines, now))
        writeSequence(prefs, sequence)

        prefs.edit()
            .putLong(LAST_SYNC, now)
            .putLong(LAST_EVENT_TIME, lastEventTime)
            .putString(LAST_EVENT_PACKAGE, lastEventPackage)
            .apply()

        return SyncResult(
            processed,
            transitionUpdates,
            sessionUpdates,
            routineUpdates,
            true
        )
    }

    fun transitions(context: Context): List<TransitionPattern> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return readTransitions(prefs).values
            .filter { !expired(it.lastSeen, now) }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    fun sessions(context: Context): List<SessionPattern> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return readSessions(prefs).values
            .filter { !expired(it.lastSeen, now) }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    fun routineCandidates(context: Context): List<RoutineCandidate> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return readRoutines(prefs).values
            .filter {
                !it.approved &&
                    it.observations >= ROUTINE_MIN_OBSERVATIONS &&
                    !expired(it.lastSeen, now)
            }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    fun approvedRoutines(context: Context): List<RoutineCandidate> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return readRoutines(prefs).values
            .filter { it.approved && !expired(it.lastSeen, now) }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
    }

    /** Explicit approval gate; this does not execute anything. */
    fun approveRoutine(context: Context, sequence: List<String>): Boolean {
        val target = normalizeSequence(sequence)
        if (target.size < 3) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val routines = readRoutines(prefs)
        val key = routineKey(target)
        val current = routines[key] ?: return false
        routines[key] = current.copy(approved = true)
        writeRoutines(prefs, pruneRoutines(routines, System.currentTimeMillis()))
        return true
    }

    fun revokeRoutineApproval(context: Context, sequence: List<String>): Boolean {
        val target = normalizeSequence(sequence)
        if (target.size < 3) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val routines = readRoutines(prefs)
        val key = routineKey(target)
        val current = routines[key] ?: return false
        routines[key] = current.copy(approved = false)
        writeRoutines(prefs, pruneRoutines(routines, System.currentTimeMillis()))
        return true
    }

    fun summary(context: Context): String {
        val settings = TrevorSettingsStore.load(context)
        if (!settings.usageIntelligenceEnabled) {
            return "Local behaviour learning is disabled in Settings."
        }

        val result = sync(context)
        if (!result.usageAccessGranted) {
            return "Usage Access is required before TREVOR can learn local app behaviour."
        }

        val top = transitions(context).take(5)
        val candidates = routineCandidates(context).take(5)

        return buildString {
            append("Local behaviour learning active. ")
            append(result.processedEvents).append(" new usage events processed.")

            if (top.isNotEmpty()) {
                append("\nTransitions:\n")
                top.forEach {
                    append("• ")
                        .append(shortPackage(it.fromPackage))
                        .append(" → ")
                        .append(shortPackage(it.toPackage))
                        .append(" (")
                        .append(it.observations)
                        .append(" observations, ")
                        .append((it.confidence * 100).toInt())
                        .append("% confidence)\n")
                }
            }

            if (candidates.isNotEmpty()) {
                append("Routine candidates awaiting approval: ")
                    .append(candidates.size)
                    .append(".")
            } else {
                append("No routine has enough repeated evidence yet.")
            }
        }.trim()
    }

    private fun discoverRoutines(
        existing: MutableMap<String, RoutineCandidate>,
        sequence: List<Pair<String, Long>>,
        now: Long
    ): Int {
        if (sequence.size < 3) return 0

        val packages = sequence.map { it.first }
        var updates = 0

        // Count only suffixes ending at the newly observed app. Each real
        // routine occurrence therefore contributes once to each matching length.
        // This prevents A -> B -> C -> D from counting A -> B -> C twice.
        for (length in 3..min(MAX_SEQUENCE_LENGTH, packages.size)) {
            val start = packages.size - length
            val candidate = normalizeSequence(packages.subList(start, packages.size))
            if (candidate.size != length) continue

            val key = routineKey(candidate)
            val old = existing[key]
            val observations = (old?.observations ?: 0) + 1

            existing[key] = RoutineCandidate(
                sequence = candidate,
                observations = observations,
                lastSeen = now,
                confidence = confidence(observations, now),
                approved = old?.approved == true
            )
            updates++
        }
        return updates
    }

    private fun readSequence(
        prefs: android.content.SharedPreferences
    ): ArrayList<Pair<String, Long>> {
        val array = runCatching {
            JSONArray(prefs.getString(LAST_SEQUENCE, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val result = ArrayList<Pair<String, Long>>(MAX_SEQUENCE_LENGTH)
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val pkg = o.optString("package").trim()
            val timestamp = o.optLong("time")
            if (pkg.isNotBlank() && timestamp > 0L) result += pkg to timestamp
        }
        while (result.size > MAX_SEQUENCE_LENGTH) result.removeAt(0)
        return result
    }

    private fun writeSequence(
        prefs: android.content.SharedPreferences,
        sequence: List<Pair<String, Long>>
    ) {
        val array = JSONArray()
        sequence.takeLast(MAX_SEQUENCE_LENGTH).forEach { (pkg, timestamp) ->
            array.put(
                JSONObject()
                    .put("package", pkg)
                    .put("time", timestamp)
            )
        }
        prefs.edit().putString(LAST_SEQUENCE, array.toString()).apply()
    }

    private fun normalizeSequence(sequence: List<String>): List<String> {
        val out = ArrayList<String>(sequence.size)
        sequence.forEach { raw ->
            val pkg = raw.trim()
            if (pkg.isBlank()) return@forEach
            if (out.lastOrNull() != pkg) out += pkg
        }
        return out.take(MAX_SEQUENCE_LENGTH)
    }

    private fun confidence(
        observations: Int,
        lastSeen: Long,
        now: Long = System.currentTimeMillis()
    ): Double {
        val ageDays =
            (now - lastSeen).coerceAtLeast(0L).toDouble() / TimeUnit.DAYS.toMillis(1)
        val repetition = min(1.0, observations / 10.0)
        val recency = 1.0 / (1.0 + ageDays / 14.0)
        return ((0.15 + 0.85 * repetition) * recency).coerceIn(0.0, 1.0)
    }

    private fun expired(lastSeen: Long, now: Long): Boolean =
        now - lastSeen > TimeUnit.DAYS.toMillis(RETENTION_DAYS)

    private fun shortPackage(pkg: String): String =
        pkg.substringAfterLast('.').ifBlank { pkg }.take(32)

    private fun transitionKey(from: String, to: String): String = "$from->$to"

    private fun sessionKey(pkg: String, hour: Int, weekday: Int): String =
        "$pkg|$hour|$weekday"

    private fun routineKey(sequence: List<String>): String =
        sequence.joinToString(">")

    private fun pruneTransitions(
        input: Map<String, TransitionPattern>,
        now: Long
    ): Map<String, TransitionPattern> =
        input.values
            .filter { !expired(it.lastSeen, now) }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
            .take(MAX_TRANSITIONS)
            .associateBy { transitionKey(it.fromPackage, it.toPackage) }

    private fun pruneSessions(
        input: Map<String, SessionPattern>,
        now: Long
    ): Map<String, SessionPattern> =
        input.values
            .filter { !expired(it.lastSeen, now) }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
            .take(MAX_SESSIONS)
            .associateBy { sessionKey(it.packageName, it.hourBucket, it.weekday) }

    private fun pruneRoutines(
        input: Map<String, RoutineCandidate>,
        now: Long
    ): Map<String, RoutineCandidate> =
        input.values
            .filter { !expired(it.lastSeen, now) }
            .map { it.copy(confidence = confidence(it.observations, it.lastSeen, now)) }
            .sortedByDescending { it.confidence }
            .take(MAX_ROUTINES)
            .associateBy { routineKey(it.sequence) }

    private fun readTransitions(
        prefs: android.content.SharedPreferences
    ): MutableMap<String, TransitionPattern> {
        val array = runCatching {
            JSONArray(prefs.getString(TRANSITIONS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        val out = mutableMapOf<String, TransitionPattern>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val from = o.optString("from")
            val to = o.optString("to")
            if (from.isBlank() || to.isBlank()) continue

            out[transitionKey(from, to)] = TransitionPattern(
                from,
                to,
                o.optInt("observations").coerceAtLeast(0),
                o.optLong("lastSeen"),
                o.optDouble("confidence").coerceIn(0.0, 1.0)
            )
        }
        return out
    }

    private fun writeTransitions(
        prefs: android.content.SharedPreferences,
        values: Map<String, TransitionPattern>
    ) {
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

    private fun readSessions(
        prefs: android.content.SharedPreferences
    ): MutableMap<String, SessionPattern> {
        val array = runCatching {
            JSONArray(prefs.getString(SESSIONS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        val out = mutableMapOf<String, SessionPattern>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val pkg = o.optString("package")
            if (pkg.isBlank()) continue

            val hour = o.optInt("hour")
            val weekday = o.optInt("weekday")
            out[sessionKey(pkg, hour, weekday)] = SessionPattern(
                pkg,
                hour,
                weekday,
                o.optInt("observations").coerceAtLeast(0),
                o.optLong("lastSeen"),
                o.optDouble("confidence").coerceIn(0.0, 1.0)
            )
        }
        return out
    }

    private fun writeSessions(
        prefs: android.content.SharedPreferences,
        values: Map<String, SessionPattern>
    ) {
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

    private fun readRoutines(
        prefs: android.content.SharedPreferences
    ): MutableMap<String, RoutineCandidate> {
        val array = runCatching {
            JSONArray(prefs.getString(ROUTINES, "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        val out = mutableMapOf<String, RoutineCandidate>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val seq = o.optJSONArray("sequence") ?: continue
            val sequence = buildList {
                for (j in 0 until seq.length()) {
                    val value = seq.optString(j).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
            val normalized = normalizeSequence(sequence)
            if (normalized.size < 2) continue

            out[routineKey(normalized)] = RoutineCandidate(
                normalized,
                o.optInt("observations").coerceAtLeast(0),
                o.optLong("lastSeen"),
                o.optDouble("confidence").coerceIn(0.0, 1.0),
                o.optBoolean("approved", false)
            )
        }
        return out
    }

    private fun writeRoutines(
        prefs: android.content.SharedPreferences,
        values: Map<String, RoutineCandidate>
    ) {
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

    /**
     * Schedules silent local learning through the existing WorkManager system.
     */
    fun ensureBackgroundLearning(context: Context) {
        val settings = TrevorSettingsStore.load(context)
        if (!settings.usageIntelligenceEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<TrevorBehaviorLearningWorker>(
            30L,
            TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

class TrevorBehaviorLearningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val settings = TrevorSettingsStore.load(applicationContext)
        if (!settings.usageIntelligenceEnabled) {
            return androidx.work.ListenableWorker.Result.success()
        }

        if (!TrevorBehaviorLearning.hasUsageAccess(applicationContext)) {
            return androidx.work.ListenableWorker.Result.success()
        }

        return runCatching {
            TrevorBehaviorLearning.sync(applicationContext)
            androidx.work.ListenableWorker.Result.success()
        }.getOrElse {
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
