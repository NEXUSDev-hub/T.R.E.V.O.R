package com.trevor.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TrevorProactiveEngine {
    suspend fun nextMessage(context: Context, personality: TrevorPersonality, rubbishMode: Boolean): String {
        val state = TrevorStateStore.state.value
        val settings = TrevorSettingsStore.load(context)
        val memories = TrevorPersistentMemory.longTermMemory(context)
            .takeLast(5)
            .joinToString(" | ") { it.content }

        val modeName = state.currentMode?.name ?: "NORMAL"

        val localContext = buildString {
            append("Current mode: ").append(modeName)
            append("; request state: ").append(state.requestState.name)
            if (state.lastOutput.orEmpty().isNotBlank()) {
                append("; last output: ").append(state.lastOutput.orEmpty().take(500))
            }
            if (memories.isNotBlank()) append("; approved memories: ").append(memories)
            append("; personality: ").append(personality.name)
            append("; rubbish mode: ").append(rubbishMode)
        }

        val prompt = TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" +
            "You are TREVOR's proactive decision layer. Decide whether an unsolicited message is genuinely useful right now.\n" +
            "Context:\n" + localContext + "\n\n" +
            "Rules:\n" +
            "- Return exactly one short natural-language message for the user.\n" +
            "- Make it dynamic and grounded in the supplied context; do not use a canned status line.\n" +
            "- If nothing useful is happening, say so briefly and non-intrusively.\n" +
            "- Professional = useful and calm.\n" +
            "- Chaotic/Deadpool = playful, but still context-aware.\n" +
            "- For You + rubbish mode = intentionally silly, but still dynamic.\n" +
            "- Never claim an action happened unless the context proves it."

        val ai = withContext(Dispatchers.IO) {
            TrevorMultiProviderRouter.ask(context.applicationContext, prompt, settings.preferredProvider)
        }
        return ai.getOrElse {
            val output = state.lastOutput.orEmpty().lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty()
            when {
                rubbishMode -> "I checked TREVOR's current " + modeName + " state and found no potato-related emergencies. 🥔"
                output.isNotBlank() -> "TREVOR is still tracking your latest " + modeName + " result: " + output.take(120)
                personality == TrevorPersonality.PROFESSIONAL -> "TREVOR is idle in " + modeName + " mode; nothing urgent needs your attention."
                else -> "TREVOR checked the " + modeName + " state and found it suspiciously uneventful."
            }
        }
    }
}
