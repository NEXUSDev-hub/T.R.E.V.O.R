package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrevorMemoryContext(
    val confirmedFacts: List<TrevorContextMemory.MemoryFact>,
    val recentConversation: List<TrevorConversationMessage>,
    val projectMemory: List<TrevorProjectMemory>
)

object TrevorMemoryIntelligence {
    suspend fun build(
        context: Context,
        conversationId: String,
        projectId: String? = null,
        includeConversation: Boolean = true,
        includeProject: Boolean = true
    ): TrevorMemoryContext = withContext(Dispatchers.IO) {
        TrevorMemoryContext(
            confirmedFacts = TrevorContextMemory.confirmedFacts(context, 20),
            recentConversation = if (includeConversation) TrevorPersistentMemory.recentConversation(context, conversationId, 8) else emptyList(),
            projectMemory = if (includeProject && !projectId.isNullOrBlank()) TrevorPersistentMemory.projectMemory(context, projectId).take(8) else emptyList()
        )
    }

    fun compactPrompt(memory: TrevorMemoryContext, maxChars: Int = 6000): String = buildString {
        if (memory.confirmedFacts.isNotEmpty()) {
            append("Confirmed local facts:\n")
            memory.confirmedFacts.forEach { append("- ").append(it.key).append("=").append(it.value.take(180)).append('\n') }
        }
        if (memory.projectMemory.isNotEmpty()) {
            append("Project context:\n")
            memory.projectMemory.forEach { append("- ").append(it.content.take(500)).append('\n') }
        }
        if (memory.recentConversation.isNotEmpty()) {
            append("Recent conversation:\n")
            memory.recentConversation.forEach { append(it.role).append(": ").append(it.content.take(500)).append('\n') }
        }
    }.take(maxChars)

    suspend fun clearAllLocalMemory(context: Context, conversationId: String? = null, projectId: String? = null) =
        withContext(Dispatchers.IO) {
            if (!conversationId.isNullOrBlank()) TrevorPersistentMemory.clearConversation(context, conversationId)
            if (!projectId.isNullOrBlank()) TrevorPersistentMemory.clearProjectMemory(context, projectId)
        }
}
