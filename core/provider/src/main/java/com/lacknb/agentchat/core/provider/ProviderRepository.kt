package com.lacknb.agentchat.core.provider

import android.content.Context
import com.lacknb.agentchat.core.model.ApiStyle
import com.lacknb.agentchat.core.model.ProviderProfile
import com.lacknb.agentchat.core.model.ProviderSettings
import com.lacknb.agentchat.core.model.RetrievalMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProviderRepository(
    context: Context,
    private val apiKeyStore: ApiKeyStore = AndroidKeystoreApiKeyStore(context),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())

    val settings: StateFlow<ProviderSettings> = _settings.asStateFlow()

    fun saveProvider(
        baseUrl: String,
        apiKey: String,
        model: String,
        embeddingModel: String,
        rerankModel: String,
        retrievalMode: RetrievalMode,
    ): Result<Unit> = runCatching {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedModel = model.trim()
        val normalizedEmbeddingModel = embeddingModel.trim()
        val normalizedRerankModel = rerankModel.trim()
        require(normalizedBaseUrl.startsWith("http://") || normalizedBaseUrl.startsWith("https://")) {
            "API Base URL must start with http:// or https://"
        }
        require(normalizedModel.isNotEmpty()) {
            "Model is required"
        }

        val trimmedApiKey = apiKey.trim()
        if (trimmedApiKey.isNotEmpty()) {
            apiKeyStore.save(DefaultApiKeyRef, trimmedApiKey)
        }

        prefs.edit()
            .putString(KeyBaseUrl, normalizedBaseUrl)
            .putString(KeyModel, normalizedModel)
            .putString(KeyEmbeddingModel, normalizedEmbeddingModel)
            .putString(KeyRerankModel, normalizedRerankModel)
            .putString(KeyRetrievalMode, retrievalMode.name)
            .putBoolean(KeyHasApiKey, trimmedApiKey.isNotEmpty() || prefs.getBoolean(KeyHasApiKey, false))
            .apply()

        _settings.value = loadSettings()
    }

    fun saveMcpUrl(mcpServerUrl: String) {
        prefs.edit()
            .putString(KeyMcpServerUrl, mcpServerUrl.trim())
            .apply()
        _settings.value = loadSettings()
    }

    fun saveLlmParameters(temperature: Float, topP: Float, topK: Int, maxTokens: Int) {
        prefs.edit()
            .putFloat(KeyTemperature, temperature)
            .putFloat(KeyTopP, topP)
            .putInt(KeyTopK, topK)
            .putInt(KeyMaxTokens, maxTokens)
            .apply()
        _settings.value = loadSettings()
    }

    fun currentProfile(): ProviderProfile {
        val current = settings.value
        return ProviderProfile(
            id = DefaultProviderId,
            name = "Default",
            baseUrl = current.baseUrl,
            encryptedApiKeyRef = if (current.hasApiKey) DefaultApiKeyRef else null,
            defaultModel = current.model,
            embeddingModel = current.embeddingModel,
            rerankModel = current.rerankModel,
            retrievalMode = current.retrievalMode,
            apiStyle = ApiStyle.ChatCompletions,
            mcpServerUrl = current.mcpServerUrl,
            temperature = current.temperature,
            topP = current.topP,
            topK = current.topK,
            maxTokens = current.maxTokens,
        )
    }

    fun currentApiKey(): String? {
        return apiKeyStore.load(DefaultApiKeyRef)
    }

    private fun loadSettings(): ProviderSettings {
        val hasApiKey = prefs.getBoolean(KeyHasApiKey, false)
        return ProviderSettings(
            baseUrl = prefs.getString(KeyBaseUrl, DefaultBaseUrl) ?: DefaultBaseUrl,
            model = prefs.getString(KeyModel, DefaultModel) ?: DefaultModel,
            embeddingModel = prefs.getString(KeyEmbeddingModel, DefaultEmbeddingModel) ?: DefaultEmbeddingModel,
            rerankModel = prefs.getString(KeyRerankModel, DefaultRerankModel) ?: DefaultRerankModel,
            retrievalMode = prefs.getString(KeyRetrievalMode, RetrievalMode.Keyword.name)
                ?.let { value -> RetrievalMode.entries.firstOrNull { it.name == value } }
                ?: RetrievalMode.Keyword,
            hasApiKey = hasApiKey,
            maskedApiKey = if (hasApiKey) "••••" else "",
            mcpServerUrl = prefs.getString(KeyMcpServerUrl, "") ?: "",
            temperature = prefs.getFloat(KeyTemperature, 0.7f),
            topP = prefs.getFloat(KeyTopP, 0.95f),
            topK = prefs.getInt(KeyTopK, 40),
            maxTokens = prefs.getInt(KeyMaxTokens, 8192),
        )
    }

    private companion object {
        const val PrefsName = "agentchat_provider"
        const val KeyBaseUrl = "base_url"
        const val KeyModel = "model"
        const val KeyEmbeddingModel = "embedding_model"
        const val KeyRerankModel = "rerank_model"
        const val KeyRetrievalMode = "retrieval_mode"
        const val KeyHasApiKey = "has_api_key"
        const val KeyMcpServerUrl = "mcp_server_url"
        const val KeyTemperature = "llm_temperature"
        const val KeyTopP = "llm_top_p"
        const val KeyTopK = "llm_top_k"
        const val KeyMaxTokens = "llm_max_tokens"
        const val DefaultProviderId = "default"
        const val DefaultApiKeyRef = "default_api_key"
        const val DefaultBaseUrl = "https://newapi.lacknb.edu.kg/v1"
        const val DefaultModel = "gpt-5.4-mini"
        const val DefaultEmbeddingModel = ""
        const val DefaultRerankModel = ""
    }
}
