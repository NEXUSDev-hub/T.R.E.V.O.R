package com.trevor.assistant

import android.content.Context
import org.json.JSONArray

/**
 * Small local-first memory layer. Only explicit "remember" requests are stored.
 * Gemini receives only a bounded, relevant memory context.
 */
object TrevorMemoryStore {
    private const val PREFS = "trevor_memory"
    private const val KEY = "approved"

    fun add(context: Context, memory: String) {
        val clean = memory.trim().take(1000)
        if (clean.isBlank()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(p.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val value = old.optString(i)
            if (value.isNotBlank() && !value.equals(clean, true)) next.put(value)
        }
        next.put(clean)
        while (next.length() > 50) {
            val trimmed = JSONArray()
            for (i in 1 until next.length()) trimmed.put(next.optString(i))
            p.edit().putString(KEY, trimmed.toString()).apply()
            return
        }
        p.edit().putString(KEY, next.toString()).apply()
    }

    fun all(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun relevant(context: Context, query: String, limit: Int = 6): List<String> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return all(context)
            .sortedByDescending { memory ->
                terms.count { memory.lowercase().contains(it) }
            }
            .take(limit)
    }
}
