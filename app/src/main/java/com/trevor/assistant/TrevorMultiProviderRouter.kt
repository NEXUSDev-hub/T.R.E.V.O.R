package com.trevor.assistant

import android.content.Context

object TrevorMultiProviderRouter {
    suspend fun classifyIntent(context: Context, prompt: String): Result<String> {
        val key = SecureApiKeyStore.load(context)
            ?: TrevorProviderKeyStore.load(context, TrevorProviderId.GEMINI)
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        if (key.isBlank()) return Result.failure(IllegalStateException("Gemini API key is not configured."))
        if (!TrevorProviderLimitTracker.allow(context, TrevorProviderId.GEMINI)) {
            return Result.failure(IllegalStateException("Gemini is temporarily rate-limited. Please retry after the cooldown."))
        }
        TrevorProviderLimitTracker.recordRequest(context, TrevorProviderId.GEMINI)
        val result = GeminiAiProvider.classifyIntent(
            context.applicationContext,
            key,
            TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" + prompt,
            model = GeminiAiProvider.NORMAL_MODEL
        )
        if (result.isFailure) {
            TrevorProviderLimitTracker.recordFailure(context, TrevorProviderId.GEMINI, result.exceptionOrNull())
        }
        return result
    }


    suspend fun ask(
        context: Context,
        prompt: String,
        preferred: TrevorProviderId? = TrevorProviderId.GEMINI,
        forceAdvanced: Boolean = false,
        useGoogleSearch: Boolean = false
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

        if (!TrevorProviderLimitTracker.allow(context, TrevorProviderId.GEMINI)) {
            return Result.failure(IllegalStateException("Gemini is temporarily rate-limited. Please retry after the cooldown."))
        }

        TrevorProviderLimitTracker.recordRequest(context, TrevorProviderId.GEMINI)
        var result = GeminiAiProvider.ask(
            context = context.applicationContext,
            apiKey = key,
            prompt = TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" + prompt,
            model = model,
            useGoogleSearch = useGoogleSearch
        )

        if (result.isFailure) {
            val error = result.exceptionOrNull()
            TrevorProviderLimitTracker.recordFailure(context, TrevorProviderId.GEMINI, error)

            // A complex request may fail because the advanced model is unavailable for a
            // particular key/project. Fall back once to the stable normal model, but never
            // bypass authentication/quota failures.
            val kind = TrevorErrorEngine.classify(error?.message.orEmpty())
            if (model == GeminiAiProvider.ADVANCED_MODEL &&
                kind != TrevorErrorEngine.Kind.AUTH &&
                kind != TrevorErrorEngine.Kind.QUOTA &&
                kind != TrevorErrorEngine.Kind.RATE_LIMIT
            ) {
                if (TrevorProviderLimitTracker.allow(context, TrevorProviderId.GEMINI)) {
                    TrevorProviderLimitTracker.recordRequest(context, TrevorProviderId.GEMINI)
                    result = GeminiAiProvider.ask(
                        context = context.applicationContext,
                        apiKey = key,
                        prompt = TrevorIdentity.IMMUTABLE_DIRECTIVE + "\n\n" + prompt,
                        model = GeminiAiProvider.NORMAL_MODEL,
                        useGoogleSearch = useGoogleSearch
                    )
                    if (result.isFailure) {
                        TrevorProviderLimitTracker.recordFailure(
                            context,
                            TrevorProviderId.GEMINI,
                            result.exceptionOrNull()
                        )
                    }
                }
            }
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
        if (!TrevorProviderLimitTracker.allow(context, TrevorProviderId.GEMINI)) {
            return Result.failure(IllegalStateException("Gemini is temporarily rate-limited. Please retry after the cooldown."))
        }
        TrevorProviderLimitTracker.recordRequest(context, TrevorProviderId.GEMINI)
        val result = GeminiAiProvider.ask(
            context.applicationContext,
            key,
            prompt,
            model = GeminiAiProvider.NORMAL_MODEL
        )
        if (result.isFailure) {
            TrevorProviderLimitTracker.recordFailure(context, TrevorProviderId.GEMINI, result.exceptionOrNull())
        }
        return result
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
