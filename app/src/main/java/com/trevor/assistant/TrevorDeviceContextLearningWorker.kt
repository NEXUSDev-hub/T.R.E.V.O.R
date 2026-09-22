package com.trevor.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodically samples compact device context and associates it with the
 * currently foreground app when Usage Access is available.
 *
 * This worker performs learning only. It never launches apps or executes
 * routines and stores only the derived fields maintained by
 * TrevorDeviceContextLearning.
 */
class TrevorDeviceContextLearningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = TrevorSettingsStore.load(applicationContext)
        if (!settings.usageIntelligenceEnabled) return Result.success()
        if (!TrevorDeviceContextLearning.hasUsageAccess(applicationContext)) return Result.success()

        return runCatching {
            TrevorDeviceContextLearning.recordCurrentForegroundContext(applicationContext)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}

object TrevorDeviceContextLearningScheduler {
    private const val WORK_NAME = "trevor_device_context_learning"
    private const val INITIAL_WORK_NAME = "trevor_device_context_learning_initial"

    fun ensureScheduled(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<TrevorDeviceContextLearningWorker>(
            30,
            TimeUnit.MINUTES
        ).build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        // Use WorkManager for the first sample too, so UsageStats access and
        // SharedPreferences I/O stay off the Activity/main thread and survive
        // short process-lifecycle changes.
        if (TrevorDeviceContextLearning.hasUsageAccess(context)) {
            val initialRequest = OneTimeWorkRequestBuilder<TrevorDeviceContextLearningWorker>()
                .build()
            workManager.enqueueUniqueWork(
                INITIAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                initialRequest
            )
        }

    }
}
