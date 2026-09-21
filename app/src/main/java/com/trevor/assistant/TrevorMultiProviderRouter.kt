package com.trevor.assistant

import android.content.Context

object TrevorMultiProviderRouter {
    suspend fun ask(context: Context, prompt: String, preferred: TrevorProviderId? = TrevorProviderId.GEMINI): Result<String> {
        val key = SecureApiKeyStore.load(context)
            ?: TrevorProviderKeyStore.load(context, TrevorProviderId.GEMINI)
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        if (key.isBlank()) return Result.failure(IllegalStateException("Gemini API key is not configured."))
        TrevorProviderLimitTracker.recordRequest(context, TrevorProviderId.GEMINI)
        val result = GeminiAiProvider.ask(
            context.applicationContext,
            key,
            TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" + prompt
        )
        if (result.isFailure) TrevorProviderLimitTracker.recordFailure(context, TrevorProviderId.GEMINI, result.exceptionOrNull())
        return result
    }

    suspend fun testSingle(context: Context, provider: TrevorProviderId = TrevorProviderId.GEMINI, prompt: String = "Reply with exactly: TREVOR connection test successful."): Result<String> {
        val key = SecureApiKeyStore.load(context)
            ?: TrevorProviderKeyStore.load(context, TrevorProviderId.GEMINI)
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return GeminiAiProvider.ask(context.applicationContext, key, prompt)
    }
}
