package com.lacknb.agentchat.core.provider

import android.content.Context
import com.lacknb.agentchat.core.model.ApiStyle
import com.lacknb.agentchat.core.model.ProviderProfile
import com.lacknb.agentchat.core.model.ProviderSettings
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
    ): Result<Unit> = runCatching {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedModel = model.trim()
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
            .putBoolean(KeyHasApiKey, trimmedApiKey.isNotEmpty() || prefs.getBoolean(KeyHasApiKey, false))
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
            apiStyle = ApiStyle.ChatCompletions,
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
            hasApiKey = hasApiKey,
            maskedApiKey = if (hasApiKey) "••••" else "",
        )
    }

    private companion object {
        const val PrefsName = "agentchat_provider"
        const val KeyBaseUrl = "base_url"
        const val KeyModel = "model"
        const val KeyHasApiKey = "has_api_key"
        const val DefaultProviderId = "default"
        const val DefaultApiKeyRef = "default_api_key"
        const val DefaultBaseUrl = "https://newapi.lacknb.edu.kg/v1"
        const val DefaultModel = "gpt-5.4-mini"
    }
}
