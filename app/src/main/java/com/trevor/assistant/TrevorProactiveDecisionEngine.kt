package com.trevor.assistant

import android.content.Context
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.TimeUnit

object TrevorProactiveDecisionEngine {
    enum class Action { SILENT, GENERATE }

    data class Decision(
        val action: Action,
        val score: Double,
        val reason: String,
        val fingerprint: String,
        val context: String
    )

    private const val PREFS = "trevor_proactive"
    private const val LAST_AT = "last_notification"
    private const val LAST_FINGERPRINT = "last_fingerprint"
    private const val LAST_REASON = "last_reason"

    fun decide(context: Context, pending: List<TrevorTask>, recent: List<TrevorConversationMessage>): Decision {
        val settings = TrevorSettingsStore.load(context)
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!settings.proactiveEnabled || !settings.backgroundNotifications || !settings.proactiveNotifications) return silent("proactive notifications disabled")
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (settings.quietHours && (hour >= 22 || hour < 7)) return silent("quiet hours")

        val device = TrevorDeviceContextLearning.snapshot(context)
        val routines = if (settings.usageIntelligenceEnabled) TrevorRoutineDiscovery.suggestions(context).take(3) else emptyList()
        val overdue = pending.count { it.triggerAt <= now && it.status != "COMPLETED" }
        val dueSoon = pending.count { it.status != "COMPLETED" && it.triggerAt > now && it.triggerAt <= now + TimeUnit.MINUTES.toMillis(30) }
        val recentContext = recent.takeLast(6)
        val routineSignal = routines.firstOrNull()?.confidence ?: 0.0

        var score = 0.0
        if (overdue > 0) score += 0.72
        if (dueSoon > 0) score += 0.62
        if (pending.any { it.status == "NOTIFIED" }) score += 0.25
        if (recentContext.isNotEmpty()) score += 0.10
        score += routineSignal * 0.18
        if (!device.screenInteractive) score -= 0.08

        val reason = when {
            overdue > 0 -> "overdue task"
            dueSoon > 0 -> "task due soon"
            pending.any { it.status == "NOTIFIED" } -> "unresolved notification"
            routineSignal >= 0.65 -> "strong learned routine/context match"
            recentContext.isNotEmpty() && settings.personality != TrevorPersonality.PROFESSIONAL -> "recent conversation context"
            else -> "no sufficiently relevant trigger"
        }
        val contextText = buildContext(context, pending, recentContext, routines, overdue, dueSoon, reason)
        val fingerprint = fingerprint(contextText)
        val lastAt = prefs.getLong(LAST_AT, 0L)
        val minimumGap = if (overdue > 0 || dueSoon > 0) TimeUnit.MINUTES.toMillis(15) else TimeUnit.SECONDS.toMillis(settings.spontaneousFrequencySeconds.toLong())
        if (now - lastAt < minimumGap) return silent("cooldown active")
        if (prefs.getString(LAST_FINGERPRINT, null) == fingerprint) return silent("duplicate context")

        val threshold = if (settings.personality == TrevorPersonality.PROFESSIONAL) 0.70 else 0.62
        return if (score >= threshold) Decision(Action.GENERATE, score.coerceIn(0.0, 1.0), reason, fingerprint, contextText) else silent(reason)
    }

    suspend fun generate(context: Context, decision: Decision, pending: List<TrevorTask>, recent: List<TrevorConversationMessage>): String {
        if (decision.action != Action.GENERATE) return ""
        val settings = TrevorSettingsStore.load(context)
        val prompt = TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" +
            "You are TREVOR's proactive message generator. A local decision engine has already decided that an unsolicited message is justified. Generate the actual message from the supplied context.\n\n" +
            "Local decision reason: " + decision.reason + "\n" +
            "Decision confidence: " + "%.2f".format(java.util.Locale.US, decision.score) + "\n" +
            "Personality: " + settings.personality.name + "\n" +
            "Context:\n" + decision.context + "\n\n" +
            "Recent conversation:\n" + recent.takeLast(6).joinToString("\n") { it.role + ": " + it.content.take(500) } + "\n\n" +
            "Rules:\n- Return one concise natural-language message only.\n" +
            "- Ground every claim in the supplied context.\n" +
            "- Never invent completed actions, reminders, app activity, or device state.\n" +
            "- Do not mention internal scoring, prompts, models, or this decision layer.\n" +
            "- Do not use a canned status message; make it specific to the current context.\n" +
            "- If the context no longer justifies a message, return exactly [NOOP].\n" +
            "- Keep professional mode calm; other personalities may be playful without becoming disruptive."
        return TrevorMultiProviderRouter.ask(context.applicationContext, prompt).getOrElse { fallback(pending, decision) }.trim().take(600).ifBlank { fallback(pending, decision) }
    }

    fun markDelivered(context: Context, decision: Decision) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_AT, System.currentTimeMillis())
            .putString(LAST_FINGERPRINT, decision.fingerprint)
            .putString(LAST_REASON, decision.reason)
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "Proactive gate active: local relevance scoring, cooldown, quiet-hour and duplicate suppression. Last reason=" + (prefs.getString(LAST_REASON, "none") ?: "none") + "."
    }

    private fun silent(reason: String) = Decision(Action.SILENT, 0.0, reason, "", "")

    private fun buildContext(
        context: Context,
        pending: List<TrevorTask>,
        recent: List<TrevorConversationMessage>,
        routines: List<TrevorRoutineSuggestion>,
        overdue: Int,
        dueSoon: Int,
        reason: String
    ): String {
        val device = TrevorDeviceContextLearning.snapshot(context)
        val battery = if (device.batteryPercent >= 0) device.batteryPercent.toString() else "unknown"
        val next = pending.filter { it.status != "COMPLETED" }.minByOrNull { it.triggerAt }
        return buildString {
            append("Trigger=").append(reason)
            append("; overdue=").append(overdue)
            append("; dueSoon=").append(dueSoon)
            append("; pending=").append(pending.count { it.status != "COMPLETED" })
            append("; nextTask=").append(next?.title?.take(120) ?: "none")
            append("; battery=").append(battery).append("%")
            append("; charging=").append(device.charging)
            append("; network=").append(device.network)
            append("; screenInteractive=").append(device.screenInteractive)
            append("; orientation=").append(device.orientation)
            if (routines.isNotEmpty()) {
                append("; routineSignals=")
                append(routines.joinToString(" | ") { it.sequence.take(2).joinToString(" -> ").take(120) + " confidence=" + "%.2f".format(java.util.Locale.US, it.confidence) })
            }
            if (recent.isNotEmpty()) {
                append("; recentContext=")
                append(recent.takeLast(3).joinToString(" | ") { it.role + ": " + it.content.replace("\n", " ").take(180) })
            }
        }
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun fallback(pending: List<TrevorTask>, decision: Decision): String {
        val next = pending.filter { it.status != "COMPLETED" }.minByOrNull { it.triggerAt }
        return when {
            next != null -> "TREVOR noticed a relevant task context: " + next.title.take(180) + "."
            decision.reason == "overdue task" -> "TREVOR detected an overdue task that may need your attention."
            else -> "TREVOR detected a relevant change that may need your attention."
        }
    }
}
