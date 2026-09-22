package com.trevor.assistant

import android.content.Context

/**
 * AI budget is a ceiling, not a quota.
 *
 * TREVOR should normally use zero AI calls when local intelligence can solve
 * the task. A complex task may use one call for planning/reasoning and, only
 * if local execution/recovery genuinely needs it, one additional call.
 */
object TrevorAiBudget {
    private const val PREFS = "trevor_ai_budget"
    private const val MAX_CALLS_PER_TASK = 2
    private const val COUNT_PREFIX = "calls:"

    fun canUse(context: Context, taskId: String): Boolean =
        callsUsed(context, taskId) < MAX_CALLS_PER_TASK

    /**
     * Consume a call only at the point where Gemini is actually invoked.
     * This does not reserve or pre-spend a call.
     */
    fun consume(context: Context, taskId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = COUNT_PREFIX + taskId
        val current = prefs.getInt(key, 0)
        if (current >= MAX_CALLS_PER_TASK) return false
        prefs.edit().putInt(key, current + 1).apply()
        return true
    }

    fun callsUsed(context: Context, taskId: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(COUNT_PREFIX + taskId, 0)

    fun remaining(context: Context, taskId: String): Int =
        (MAX_CALLS_PER_TASK - callsUsed(context, taskId)).coerceAtLeast(0)

    fun clear(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(COUNT_PREFIX + taskId).apply()
    }
}
