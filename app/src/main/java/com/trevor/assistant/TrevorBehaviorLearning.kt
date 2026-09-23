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
    private const val CURRENT_SESSION_START = "current_session_start"
    private const val CURRENT_SESSION_END = "current_session_end"
    private const val CURRENT_SESSION_APPS = "current_session_apps"

    private const val MAX_TRANSITIONS = 200
    private const val MAX_SESSIONS = 160
    private const val MAX_ROUTINES = 120
    private const val MAX_SEQUENCE_LENGTH = 6
    private const val SESSION_GAP_MS = 30L * 60L * 1000L
    private const val RETENTION_DAYS = 45L
    private const val ROUTINE_MIN_OBSERVATIONS = 3
    private const val WORK_NAME = "trevor_behavior_learning"

    private val storageLock = Any()

    data class TransitionPattern(
        val fromPackage: String,
        val toPackage: String,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    )

    data class SessionPattern(
        val sessionStart: Long,
        val sessionEnd: Long,
        val durationMs: Long,
        val apps: List<String>,
        val hourBucket: Int,
        val weekday: Int,
        val observations: Int,
        val lastSeen: Long,
        val confidence: Double
    ) {
        val packageName: String
            get() = apps.firstOrNull().orEmpty()
    }

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
        synchronized(storageLock) {
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

        var currentSessionStart = prefs.getLong(CURRENT_SESSION_START, 0L)
        var currentSessionEnd = prefs.getLong(CURRENT_SESSION_END, 0L)
        val currentSessionApps = readStringList(prefs, CURRENT_SESSION_APPS).toMutableList()
        if (currentSessionEnd > 0L && now - currentSessionEnd > SESSION_GAP_MS) {
            currentSessionStart = 0L
            currentSessionEnd = 0L
            currentSessionApps.clear()
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
            val newSession = lastTimestamp <= 0L || timestamp - lastTimestamp > SESSION_GAP_MS
            if (newSession || currentSessionStart <= 0L) {
                currentSessionStart = timestamp
                currentSessionApps.clear()
                currentSessionApps += pkg
                sessionUpdates++
            } else if (currentSessionApps.lastOrNull() != pkg) {
                currentSessionApps += pkg
                sessionUpdates++
            }
            currentSessionEnd = timestamp

            val startCalendar = Calendar.getInstance().apply { timeInMillis = currentSessionStart }
            val session = SessionPattern(
                sessionStart = currentSessionStart,
                sessionEnd = currentSessionEnd,
                durationMs = (currentSessionEnd - currentSessionStart).coerceAtLeast(0L),
                apps = currentSessionApps.distinct(),
                hourBucket = startCalendar.get(Calendar.HOUR_OF_DAY) / 2,
                weekday = startCalendar.get(Calendar.DAY_OF_WEEK),
                observations = 1,
                lastSeen = currentSessionEnd,
                confidence = confidence(1, currentSessionEnd)
            )
            sessions[sessionKey(currentSessionStart)] = session

            // A duplicate ACTIVITY_RESUMED for the same app does not create
            // another routine occurrence. Only an actual sequence transition
            // advances routine learning.
            val sequenceChanged = sequence.isEmpty() || sequence.last().first != pkg
            if (sequenceChanged) {
                sequence += pkg to timestamp
            } else {
                sequence[sequence.lastIndex] = pkg to timestamp
            }
            while (sequence.size > MAX_SEQUENCE_LENGTH) sequence.removeAt(0)

            if (sequenceChanged) {
                routineUpdates += discoverRoutines(routines, sequence, timestamp)
            }

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

        val sessionEditor = prefs.edit()
        if (currentSessionStart > 0L && currentSessionEnd > 0L) {
            sessionEditor
                .putLong(CURRENT_SESSION_START, currentSessionStart)
                .putLong(CURRENT_SESSION_END, currentSessionEnd)
                .putString(CURRENT_SESSION_APPS, JSONArray(currentSessionApps).toString())
        }
        sessionEditor
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
            .filter { it.approved }
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

    private fun readStringList(prefs: android.content.SharedPreferences, key: String): List<String> {
        val array = runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }.distinct()
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

    private fun writeSessions(
        prefs: android.content.SharedPreferences,
        values: Map<String, SessionPattern>
    ) {
        val array = JSONArray()
        values.values.forEach {
            val apps = JSONArray()
            it.apps.forEach(apps::put)
            array.put(
                JSONObject()
                    .put("sessionStart", it.sessionStart)
                    .put("sessionEnd", it.sessionEnd)
                    .put("durationMs", it.durationMs)
                    .put("apps", apps)
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
