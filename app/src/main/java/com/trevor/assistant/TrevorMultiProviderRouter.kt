package com.trevor.assistant

import android.content.Context

/**
 * Compatibility name retained for existing call sites.
 * TREVOR now has one provider (Google Gemini) and automatically chooses
 * the model locally: 3.6 Flash for ordinary requests and 3.8 Flash for
 * complex/agentic requests.
 */
object TrevorMultiProviderRouter {
    suspend fun ask(
        context: Context,
        prompt: String,
        preferred: TrevorProviderId? = TrevorProviderId.GEMINI,
        forceAdvanced: Boolean = false
    ): Result<String> {
        val key = SecureApiKeyStore.load(context)
            ?: TrevorProviderKeyStore.load(context, TrevorProviderId.GEMINI)
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        if (key.isBlank()) return Result.failure(IllegalStateException("Gemini API key is not configured."))

        val model = if (forceAdvanced || TrevorAiRouting.isComplex(prompt)) {
            GeminiAiProvider.ADVANCED_MODEL
        } else {
            GeminiAiProvider.NORMAL_MODEL
        }

        TrevorProviderLimitTracker.recordRequest(context, TrevorProviderId.GEMINI)
        val result = GeminiAiProvider.ask(
            context = context.applicationContext,
            apiKey = key,
            prompt = TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" + prompt,
            model = model
        )
        if (result.isFailure) {
            TrevorProviderLimitTracker.recordFailure(
                context,
                TrevorProviderId.GEMINI,
                result.exceptionOrNull()
            )
        }
        return result
    }

    suspend fun testSingle(
        context: Context,
        provider: TrevorProviderId = TrevorProviderId.GEMINI,
        prompt: String = "Reply with exactly: TREVOR connection test successful."
    ): Result<String> {
        val key = SecureApiKeyStore.load(context)
            ?: TrevorProviderKeyStore.load(context, TrevorProviderId.GEMINI)
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return GeminiAiProvider.ask(
            context.applicationContext,
            key,
            prompt,
            model = GeminiAiProvider.NORMAL_MODEL
        )
    }
}

object TrevorAiRouting {
    fun isComplex(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val complexityWords = listOf(
            "plan", "debug", "architecture", "automate", "automation", "multi-step",
            "analyse", "analyze", "compare", "research", "implement", "code",
            "document", "pdf", "ocr", "workflow", "project", "reason", "solve"
        )
        val longRequest = prompt.length > 900
        val manySteps = Regex("""\b(then|after that|next|finally|step)\b""").findAll(lower).count() >= 2
        val explicitComplexity = complexityWords.count { lower.contains(it) } >= 2
        return longRequest || manySteps || explicitComplexity
    }
}
