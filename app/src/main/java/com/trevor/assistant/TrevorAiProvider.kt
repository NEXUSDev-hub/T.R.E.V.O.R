package com.trevor.assistant

import android.content.Context

enum class TrevorProviderId(val displayName: String) {
    GEMINI("Google Gemini")
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

enum class TrevorProviderProtocol { GEMINI }

object TrevorProviderRegistry {
    // Conservative free-tier registry: only the currently verified free provider
    // that is suitable for this app's age-appropriate account path.
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
