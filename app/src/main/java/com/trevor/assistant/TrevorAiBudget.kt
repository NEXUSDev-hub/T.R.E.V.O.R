package com.trevor.assistant

import android.content.Context

/**
 * Tracks AI usage for observability, not as a hard task limit.
 *
 * TREVOR should minimize AI calls by using local intelligence whenever possible,
 * but a complex task may make as many Gemini requests as genuinely required.
 * There is deliberately NO maximum-call block here.
 */
object TrevorAiBudget {
    private const val PREFS = "trevor_ai_budget"
    private const val COUNT_PREFIX = "calls:"

    fun canUse(context: Context, taskId: String): Boolean = true

    /**
     * Record an actual AI invocation. This is called only immediately before
     * Gemini is invoked; it never reserves or blocks a future call.
     */
    fun consume(context: Context, taskId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = COUNT_PREFIX + taskId
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, current + 1).apply()
        return true
    }

    fun callsUsed(context: Context, taskId: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(COUNT_PREFIX + taskId, 0)

    fun remaining(context: Context, taskId: String): Int = Int.MAX_VALUE

    fun clear(context: Context, taskId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(COUNT_PREFIX + taskId).apply()
    }
}
