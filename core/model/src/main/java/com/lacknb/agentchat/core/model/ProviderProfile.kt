package com.lacknb.agentchat.core.model

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val encryptedApiKeyRef: String?,
    val defaultModel: String,
    val embeddingModel: String,
    val rerankModel: String,
    val retrievalMode: RetrievalMode,
    val apiStyle: ApiStyle,
    val timeoutSeconds: Int = 60,
    val mcpServerUrl: String = "",
    val enabled: Boolean = true,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 8192,
)

enum class ApiStyle {
    ChatCompletions,
}

enum class RetrievalMode(val displayName: String) {
    Keyword("关键字检索"),
    Vector("向量检索"),
    Hybrid("混合检索"),
}

data class ProviderSettings(
    val baseUrl: String = "https://newapi.lacknb.edu.kg/v1",
    val model: String = "gpt-5.4-mini",
    val embeddingModel: String = "",
    val rerankModel: String = "",
    val retrievalMode: RetrievalMode = RetrievalMode.Keyword,
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val mcpServerUrl: String = "",
    val tavilyApiKey: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 8192,
)
