package com.trevor.assistant

import android.content.Context

enum class TrevorProviderId(val displayName: String) {
    GEMINI("Google Gemini"),
    GROQ("Groq"),
    OPENROUTER("OpenRouter")
}

data class TrevorModelSpec(
    val id: String,
    val label: String,
    val stable: Boolean = true,
    val multimodal: Boolean = false
)

data class TrevorProviderSpec(
    val id: TrevorProviderId,
    val displayName: String,
    val endpoint: String,
    val models: List<TrevorModelSpec>,
    val protocol: TrevorProviderProtocol,
    val genuinelyFree: Boolean = true
)

enum class TrevorProviderProtocol { GEMINI, OPENAI_COMPATIBLE }

object TrevorProviderRegistry {
    /*
     * TREVOR deliberately keeps only providers with a documented $0 API path.
     * Free-tier availability can change upstream, so this registry is intentionally
     * small and conservative rather than advertising paid-only providers as free.
     */
    val providers = listOf(
        TrevorProviderSpec(
            TrevorProviderId.GEMINI,
            "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            listOf(
                TrevorModelSpec("gemini-3.8-flash", "Gemini 3.8 Flash", multimodal = true),
                TrevorModelSpec("gemini-3.7-flash", "Gemini 3.7 Flash", multimodal = true),
                TrevorModelSpec("gemini-3.6-flash", "Gemini 3.6 Flash", multimodal = true)
            ),
            TrevorProviderProtocol.GEMINI
        ),
        TrevorProviderSpec(
            TrevorProviderId.GROQ,
            "Groq",
            "https://api.groq.com/openai/v1",
            listOf(
                TrevorModelSpec("openai/gpt-oss-120b", "GPT-OSS 120B"),
                TrevorModelSpec("openai/gpt-oss-20b", "GPT-OSS 20B")
            ),
            TrevorProviderProtocol.OPENAI_COMPATIBLE
        ),
        TrevorProviderSpec(
            TrevorProviderId.OPENROUTER,
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            listOf(
                TrevorModelSpec("openrouter/free", "OpenRouter Free Models Router", multimodal = true)
            ),
            TrevorProviderProtocol.OPENAI_COMPATIBLE
        )
    )

    fun spec(id: TrevorProviderId): TrevorProviderSpec = providers.first { it.id == id }

    fun findModel(provider: TrevorProviderId, modelId: String?): TrevorModelSpec? =
        modelId?.let { id -> spec(provider).models.firstOrNull { it.id == id } }

    fun modelsFor(provider: TrevorProviderId): List<TrevorModelSpec> = spec(provider).models
}

interface TrevorAiProvider {
    val id: TrevorProviderId
    suspend fun ask(context: Context, apiKey: String, model: TrevorModelSpec, prompt: String): Result<String>
}
