package com.trevor.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TrevorProactiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = TrevorSettingsStore.load(applicationContext)
        if (!settings.proactiveEnabled || !settings.backgroundNotifications) return Result.success()
        val message = TrevorProactiveEngine.nextMessage(applicationContext, settings.personality, settings.rubbishMode)
        if (message.trim().equals("[NOOP]", ignoreCase = true)) return Result.success()
        TrevorStateStore.update { it.copy(lastOutput = "TREVOR • proactive\n$message") }
        TrevorNotificationHelper.show(applicationContext, message, settings.personality)
        return Result.success()
    }
}
