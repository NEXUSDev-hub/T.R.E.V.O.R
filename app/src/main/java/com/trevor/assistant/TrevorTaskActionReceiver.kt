package com.trevor.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TrevorTaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TrevorAutomationNotificationHelper.EXTRA_TASK_ID) ?: return
        when (intent.action) {
            TrevorAutomationNotificationHelper.ACTION_COMPLETE ->
                TrevorAutomationEngine.completeManually(context, taskId)

            TrevorAutomationNotificationHelper.ACTION_SNOOZE -> {
                val delay = intent.getLongExtra(
                    TrevorAutomationNotificationHelper.EXTRA_DELAY_MILLIS,
                    15L * 60L * 1000L
                )
                TrevorAutomationEngine.snooze(context, taskId, delay)
            }
        }
    }
}
