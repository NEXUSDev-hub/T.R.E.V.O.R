package com.trevor.assistant

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class TrevorAutomationWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        val task = TrevorAutomationEngine.loadTask(applicationContext, taskId) ?: return Result.failure()

        return try {
            setForeground(createForegroundInfo(task))
            val result = TrevorAutomationEngine.executeDetailed(applicationContext, task.plan)
            TrevorStateStore.update { it.copy(lastOutput = result.message) }
            TrevorAutomationEngine.loadTask(applicationContext, taskId)?.let {
                TrevorAutomationNotificationHelper.show(applicationContext, it, result.message)
            }

            when {
                result.cancelled || result.completed -> Result.success()
                result.retryable && !isStopped -> Result.retry()
                else -> Result.failure()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            TrevorAutomationEngine.loadTask(applicationContext, taskId)?.let {
                if (it.state != TrevorAutomationTaskState.COMPLETED) {
                    TrevorAutomationEngine.cancel(applicationContext, taskId)
                }
            }
            throw cancelled
        }
    }

    private fun createForegroundInfo(task: TrevorAutomationTask): ForegroundInfo {
        val channelId = "trevor_automation_running"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "TREVOR automation",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(
                applicationContext,
                task.id.hashCode(),
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TREVOR • Automation running")
            .setContentText(task.title)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()

        return ForegroundInfo(("trevor-run-" + task.id).hashCode(), notification)
    }
}
