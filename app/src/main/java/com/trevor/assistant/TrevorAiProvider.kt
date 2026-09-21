package com.trevor.assistant

import android.content.Context

enum class TrevorProviderId(val displayName: String) {
    GEMINI("Google Gemini"),
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    XAI("xAI"),
    MISTRAL("Mistral AI"),
    DEEPSEEK("DeepSeek"),
    COHERE("Cohere"),
    GROQ("Groq"),
    TOGETHER("Together AI"),
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
    val protocol: TrevorProviderProtocol
)

enum class TrevorProviderProtocol { GEMINI, OPENAI_COMPATIBLE, ANTHROPIC, COHERE }

object TrevorProviderRegistry {
    val providers = listOf(
        TrevorProviderSpec(TrevorProviderId.GEMINI, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", listOf(
            TrevorModelSpec("gemini-3.8-flash", "Gemini 3.8 Flash", multimodal = true),
            TrevorModelSpec("gemini-3.7-flash", "Gemini 3.7 Flash", multimodal = true)
        ), TrevorProviderProtocol.GEMINI),
        TrevorProviderSpec(TrevorProviderId.OPENAI, "OpenAI", "https://api.openai.com/v1", listOf(
            TrevorModelSpec("gpt-5.1", "GPT-5.1", multimodal = true),
            TrevorModelSpec("gpt-5", "GPT-5", multimodal = true)
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE),
        TrevorProviderSpec(TrevorProviderId.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1", listOf(
            TrevorModelSpec("claude-opus-5", "Claude Opus 5", multimodal = true),
            TrevorModelSpec("claude-opus-4-8", "Claude Opus 4.8", multimodal = true)
        ), TrevorProviderProtocol.ANTHROPIC),
        TrevorProviderSpec(TrevorProviderId.XAI, "xAI", "https://api.x.ai/v1", listOf(
            TrevorModelSpec("grok-4.6", "Grok 4.6", multimodal = true),
            TrevorModelSpec("grok-4.5", "Grok 4.5", multimodal = true)
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE),
        TrevorProviderSpec(TrevorProviderId.MISTRAL, "Mistral AI", "https://api.mistral.ai/v1", listOf(
            TrevorModelSpec("mistral-medium-3.5", "Mistral Medium 3.5", multimodal = true),
            TrevorModelSpec("mistral-small-4", "Mistral Small 4", multimodal = true)
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE),
        TrevorProviderSpec(TrevorProviderId.DEEPSEEK, "DeepSeek", "https://api.deepseek.com", listOf(
            TrevorModelSpec("deepseek-flash", "DeepSeek V4.1 Flash", multimodal = true),
            TrevorModelSpec("deepseek-v4-pro", "DeepSeek V4 Pro")
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE),
        TrevorProviderSpec(TrevorProviderId.COHERE, "Cohere", "https://api.cohere.com/v2", listOf(
            TrevorModelSpec("command-a-plus-05-2026", "Command A+"),
            TrevorModelSpec("command-a-03-2025", "Command A")
        ), TrevorProviderProtocol.COHERE),
        TrevorProviderSpec(TrevorProviderId.GROQ, "Groq", "https://api.groq.com/openai/v1", listOf(
            TrevorModelSpec("openai/gpt-oss-120b", "GPT-OSS 120B"),
            TrevorModelSpec("openai/gpt-oss-20b", "GPT-OSS 20B")
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE),
        TrevorProviderSpec(TrevorProviderId.TOGETHER, "Together AI", "https://api.together.xyz/v1", listOf(
            TrevorModelSpec("openai/gpt-oss-120b", "GPT-OSS 120B"),
            TrevorModelSpec("openai/gpt-oss-20b", "GPT-OSS 20B")
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE),
        TrevorProviderSpec(TrevorProviderId.OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", listOf(
            TrevorModelSpec("openai/gpt-5.1", "GPT-5.1 via OpenRouter"),
            TrevorModelSpec("google/gemini-3.7-flash", "Gemini 3.7 Flash via OpenRouter")
        ), TrevorProviderProtocol.OPENAI_COMPATIBLE)
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
