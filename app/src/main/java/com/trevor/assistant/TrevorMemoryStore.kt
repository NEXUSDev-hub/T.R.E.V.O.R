package com.trevor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Part 15: bounded local conversation/project memory. */
data class TrevorConversationMessage(val role: String, val content: String, val time: Long)
data class TrevorProjectMemory(val projectId: String, val content: String, val time: Long)

object TrevorPersistentMemory {
    private const val PREFS = "trevor_persistent_memory_v1"
    private const val MAX_MESSAGES = 200
    private const val MAX_PROJECT_ITEMS = 120

    fun rememberMessage(context: Context, conversationId: String, role: String, content: String) {
        if (conversationId.isBlank() || content.isBlank()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = JSONArray(p.getString("c:$conversationId", "[]"))
        a.put(JSONObject().put("role", role.take(32)).put("content", content.take(4000)).put("time", System.currentTimeMillis()))
        while (a.length() > MAX_MESSAGES) a.remove(0)
        p.edit().putString("c:$conversationId", a.toString()).apply()
    }

    fun recentConversation(context: Context, conversationId: String, limit: Int): List<TrevorConversationMessage> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = JSONArray(p.getString("c:$conversationId", "[]"))
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { TrevorConversationMessage(it.optString("role"), it.optString("content"), it.optLong("time")) }
        }.takeLast(limit.coerceIn(1, 50))
    }

    fun rememberProject(context: Context, projectId: String, content: String) {
        if (projectId.isBlank() || content.isBlank()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "p:$projectId"
        val a = JSONArray(p.getString(key, "[]"))
        a.put(JSONObject().put("content", content.take(5000)).put("time", System.currentTimeMillis()))
        while (a.length() > MAX_PROJECT_ITEMS) a.remove(0)
        p.edit().putString(key, a.toString()).apply()
    }

    fun projectMemory(context: Context, projectId: String): List<TrevorProjectMemory> {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("p:$projectId", "[]"))
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { TrevorProjectMemory(projectId, it.optString("content"), it.optLong("time")) }
        }.takeLast(50)
    }

    fun clearConversation(context: Context, conversationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("c:$conversationId").apply()
    }

    fun clearProjectMemory(context: Context, projectId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("p:$projectId").apply()
    }

    fun migrateLegacyMemories(context: Context) = Unit
}
