package com.trevor.assistant

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TrevorAutomationWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        val task = TrevorAutomationEngine.loadTask(applicationContext, taskId) ?: return Result.failure()

        return try {
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
}
