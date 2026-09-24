package com.trevor.assistant

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Part 18: idempotent recovery/scheduling coordinator. */
object TrevorBackgroundRecovery {
    private const val UNIQUE = "trevor_background_recovery_v1"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TrevorBackgroundRecoveryWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}

class TrevorBackgroundRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val s = TrevorSettingsStore.load(applicationContext)
        if (!s.backgroundNotifications && !s.usageIntelligenceEnabled) {
            TrevorBackgroundRecovery.cancel(applicationContext)
            return Result.success()
        }
        TrevorPersistentMemory.migrateLegacyMemories(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }
}
