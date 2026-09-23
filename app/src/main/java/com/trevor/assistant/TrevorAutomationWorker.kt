package com.trevor.assistant

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TrevorAutomationWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        val task = TrevorAutomationEngine.loadTask(applicationContext, taskId) ?: return Result.failure()
        val output = TrevorAutomationEngine.execute(applicationContext, task.plan)
        TrevorStateStore.update { it.copy(lastOutput = output) }
        return when {
            output.contains("completed successfully", true) || output.contains("already completed", true) || output.contains("cancelled", true) -> Result.success()
            else -> Result.failure()
        }
    }
}