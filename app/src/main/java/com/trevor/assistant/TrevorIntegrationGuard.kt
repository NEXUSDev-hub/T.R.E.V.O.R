package com.trevor.assistant

import android.content.Context

/** Part 20: runtime integration/regression guard. */
object TrevorIntegrationGuard {
    data class Report(
        val version: String,
        val identity: Boolean,
        val database: Boolean,
        val ai: Boolean,
        val permissions: Map<String, Boolean>,
        val modes: List<TrevorMode>,
        val warnings: List<String>
    )

    fun check(context: Context): Report {
        val warnings = mutableListOf<String>()
        val identity = TrevorIdentity.NAME == "TREVOR" &&
            TrevorIdentity.FULL_NAME.contains("The Really Efficient Virtual Operation Robot")
        val database = runCatching { TrevorDatabase.get(context).openHelper.readableDatabase.isOpen }.getOrDefault(false)
        val settings = TrevorSettingsStore.load(context)
        val ai = settings.aiEnabled && settings.geminiEnabled
        val permissions = mapOf(
            "notifications" to TrevorSecurityHardening.notificationsAllowed(context),
            "microphone" to TrevorSecurityHardening.microphoneAllowed(context),
            "overlay" to TrevorSecurityHardening.overlayAllowed(context),
            "usageAccess" to TrevorSecurityHardening.usageAccessAllowed(context),
            "visualCaptureData" to TrevorScreenCaptureStore.latest(context).isNotBlank()
        )
        if (!identity) warnings += "Identity contract mismatch."
        if (!database) warnings += "Room database unavailable."
        if (!ai) warnings += "Cloud AI is disabled; local capabilities remain available."
        return Report(TrevorVersion.label(context), identity, database, ai, permissions, TrevorMode.entries, warnings)
    }
}
