package com.lacknb.agentchat.core.network

import com.lacknb.agentchat.core.model.ProviderProfile
import kotlinx.coroutines.flow.Flow

interface ChatCompletionsClient {
    fun streamChatCompletion(
        profile: ProviderProfile,
        apiKey: String,
        request: ChatCompletionRequest,
    ): Flow<ChatCompletionStreamEvent>

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Int = 30,
    ): Result<List<String>>
}

sealed interface ChatCompletionStreamEvent {
    data class Delta(val content: String) : ChatCompletionStreamEvent
    data class ToolCallDelta(val delta: ChatCompletionToolCallDelta) : ChatCompletionStreamEvent
    data class Completed(val usage: TokenUsage?) : ChatCompletionStreamEvent
    data class Failed(val message: String, val cause: Throwable? = null) : ChatCompletionStreamEvent
}
