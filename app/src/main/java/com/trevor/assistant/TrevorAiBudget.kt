package com.trevor.assistant

import android.content.Context

object TrevorAiBudget {
    private const val PREFS = "trevor_ai_budget"
    private const val MAX_CALLS_PER_TASK = 2

    fun reserve(context: Context, taskId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(taskId, 0)
        if (count >= MAX_CALLS_PER_TASK) return false
        prefs.edit().putInt(taskId, count + 1).apply()
        return true
    }

    fun remaining(context: Context, taskId: String): Int {
        val count = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(taskId, 0)
        return (MAX_CALLS_PER_TASK - count).coerceAtLeast(0)
    }

    fun clear(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(taskId).apply()
    }
}
