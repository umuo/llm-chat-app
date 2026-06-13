package com.lacknb.agentchat.core.model

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val encryptedApiKeyRef: String?,
    val defaultModel: String,
    val apiStyle: ApiStyle,
    val timeoutSeconds: Int = 60,
    val enabled: Boolean = true,
)

enum class ApiStyle {
    ChatCompletions,
}

data class ProviderSettings(
    val baseUrl: String = "https://newapi.lacknb.edu.kg/v1",
    val model: String = "gpt-5.4-mini",
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
)
