package com.trevor.assistant

import android.content.Context
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.TimeUnit

object TrevorDynamicProactiveIntelligence {
    enum class Trigger { OVERDUE_TASK, DUE_SOON, UNRESOLVED_TASK, ROUTINE_MATCH, LOW_BATTERY, GENERAL_CONTEXT }

    data class Signal(val trigger: Trigger, val strength: Double, val key: String, val actionable: Boolean)

    data class Gate(
        val shouldNotify: Boolean,
        val score: Double,
        val trigger: Trigger?,
        val reason: String,
        val fingerprint: String,
        val context: String
    )

    private const val PREFS = "trevor_dynamic_proactive"
    private const val LAST_DELIVERED = "last_delivered"
    private const val LAST_FINGERPRINT = "last_fingerprint"
    private const val DAILY_COUNT = "daily_count"
    private const val DAILY_KEY = "daily_key"
    private const val LAST_TRIGGER = "last_trigger"
    private const val MAX_DAILY_NOTIFICATIONS = 5
    private const val MIN_GENERAL_GAP_MINUTES = 30L
    private const val MIN_ACTIONABLE_GAP_MINUTES = 10L
    private const val DUPLICATE_WINDOW_MINUTES = 180L

    fun evaluate(
        context: Context,
        pending: List<TrevorTask>,
        routineSignals: List<TrevorRoutineSuggestion>
    ): Gate {
        val settings = TrevorSettingsStore.load(context)
        val now = System.currentTimeMillis()
        if (!settings.proactiveEnabled || !settings.backgroundNotifications || !settings.proactiveNotifications) {
            return silent("proactive notifications disabled")
        }

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (settings.quietHours && (hour >= 22 || hour < 7)) return silent("quiet hours")
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return silent("notifications unavailable")
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dayKey = calendar.get(Calendar.YEAR).toString() + "-" + calendar.get(Calendar.DAY_OF_YEAR)
        val count = if (prefs.getString(DAILY_KEY, null) == dayKey) prefs.getInt(DAILY_COUNT, 0) else 0
        if (count >= MAX_DAILY_NOTIFICATIONS) return silent("daily proactive limit reached")

        val device = TrevorDeviceContextLearning.snapshot(context)
        val activeTasks = pending.filter { it.status != "COMPLETED" }
        val overdue = activeTasks.count { it.triggerAt <= now }
        val dueSoon = activeTasks.count {
            it.triggerAt > now && it.triggerAt <= now + TimeUnit.MINUTES.toMillis(30)
        }
        val unresolved = activeTasks.count { it.status == "NOTIFIED" }

        val signals = buildList {
            if (overdue > 0) add(Signal(Trigger.OVERDUE_TASK, 1.0, "overdue:" + overdue, true))
            if (dueSoon > 0) add(Signal(Trigger.DUE_SOON, 0.88, "due:" + dueSoon, true))
            if (unresolved > 0) add(Signal(Trigger.UNRESOLVED_TASK, 0.68, "unresolved:" + unresolved, true))
            val routine = routineSignals.maxByOrNull { it.confidence }
            if (routine != null && routine.confidence >= 0.68) {
                add(Signal(Trigger.ROUTINE_MATCH, routine.confidence, routine.sequence.joinToString("→"), false))
            }
            if (device.batteryPercent in 1..15 && !device.charging) {
                add(Signal(Trigger.LOW_BATTERY, 0.72, "battery:" + device.batteryPercent, true))
            }
        }.sortedByDescending { it.strength }

        val primary = signals.firstOrNull() ?: return silent("no meaningful proactive trigger")
        val lastAt = prefs.getLong(LAST_DELIVERED, 0L)
        val lastFingerprint = prefs.getString(LAST_FINGERPRINT, null)
        val actionable = primary.actionable
        val minimumGap = if (actionable) {
            TimeUnit.MINUTES.toMillis(MIN_ACTIONABLE_GAP_MINUTES)
        } else {
            TimeUnit.MINUTES.toMillis(maxOf(MIN_GENERAL_GAP_MINUTES, settings.spontaneousFrequencySeconds / 60L))
        }

        if (now - lastAt < minimumGap) return silent("adaptive cooldown")
        val currentFingerprint = fingerprint(primary, device, activeTasks)
        if (lastFingerprint == currentFingerprint &&
            now - lastAt < TimeUnit.MINUTES.toMillis(DUPLICATE_WINDOW_MINUTES)
        ) return silent("duplicate trigger")

        var score = primary.strength
        if (!device.screenInteractive && !actionable) score *= 0.55
        if (device.batteryPercent in 1..15 && !device.charging && primary.trigger != Trigger.LOW_BATTERY) score *= 0.85
        if (signals.size >= 2) score = (score + 0.08).coerceAtMost(1.0)

        val threshold = when {
            primary.actionable && settings.personality == TrevorPersonality.PROFESSIONAL -> 0.68
            primary.actionable -> 0.62
            settings.personality == TrevorPersonality.PROFESSIONAL -> 0.82
            else -> 0.76
        }
        if (score < threshold) return silent("relevance threshold not met")

        return Gate(
            true,
            score,
            primary.trigger,
            reasonFor(primary.trigger),
            currentFingerprint,
            buildContext(device, activeTasks, signals, primary)
        )
    }

    fun markDelivered(context: Context, gate: Gate) {
        if (!gate.shouldNotify) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val calendar = Calendar.getInstance()
        val dayKey = calendar.get(Calendar.YEAR).toString() + "-" + calendar.get(Calendar.DAY_OF_YEAR)
        val oldCount = if (prefs.getString(DAILY_KEY, null) == dayKey) prefs.getInt(DAILY_COUNT, 0) else 0
        prefs.edit()
            .putLong(LAST_DELIVERED, System.currentTimeMillis())
            .putString(LAST_FINGERPRINT, gate.fingerprint)
            .putString(LAST_TRIGGER, gate.trigger?.name ?: "NONE")
            .putString(DAILY_KEY, dayKey)
            .putInt(DAILY_COUNT, oldCount + 1)
            .apply()
    }

    fun markDismissed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(
                LAST_DELIVERED,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
            )
            .putString(LAST_FINGERPRINT, "dismissed")
            .putString(LAST_TRIGGER, "DISMISSED")
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "Adaptive proactive gate: daily cap=" + MAX_DAILY_NOTIFICATIONS +
            ", last trigger=" + (prefs.getString(LAST_TRIGGER, "none") ?: "none") + "."
    }

    private fun buildContext(
        device: TrevorDeviceContextLearning.ContextSnapshot,
        tasks: List<TrevorTask>,
        signals: List<Signal>,
        primary: Signal
    ): String {
        val next = tasks.minByOrNull { it.triggerAt }
        return buildString {
            append("trigger=").append(primary.trigger.name)
            append("; reason=").append(reasonFor(primary.trigger))
            append("; score=").append("%.2f".format(java.util.Locale.US, primary.strength))
            append("; activeTasks=").append(tasks.size)
            append("; nextTaskDue=").append(next?.triggerAt ?: 0L)
            append("; battery=").append(device.batteryPercent)
            append("; charging=").append(device.charging)
            append("; network=").append(device.network)
            append("; metered=").append(device.metered)
            append("; screenInteractive=").append(device.screenInteractive)
            append("; orientation=").append(device.orientation)
            append("; corroboratingSignals=").append(signals.size)
        }
    }

    private fun fingerprint(
        signal: Signal,
        device: TrevorDeviceContextLearning.ContextSnapshot,
        tasks: List<TrevorTask>
    ): String {
        val taskKey = tasks.take(3).joinToString("|") { it.id + ":" + it.status + ":" + it.triggerAt }
        val raw = listOf(
            signal.trigger.name,
            signal.key,
            device.network,
            device.charging,
            device.batteryPercent / 5,
            device.screenInteractive,
            taskKey
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun reasonFor(trigger: Trigger?): String = when (trigger) {
        Trigger.OVERDUE_TASK -> "overdue task"
        Trigger.DUE_SOON -> "task due soon"
        Trigger.UNRESOLVED_TASK -> "unresolved task notification"
        Trigger.ROUTINE_MATCH -> "repeated learned routine"
        Trigger.LOW_BATTERY -> "low battery"
        Trigger.GENERAL_CONTEXT -> "relevant context"
        null -> "no trigger"
    }

    private fun silent(reason: String) = Gate(false, 0.0, null, reason, "", "")
}
