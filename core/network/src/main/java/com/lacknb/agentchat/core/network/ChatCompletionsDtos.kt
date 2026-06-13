package com.lacknb.agentchat.core.network

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatCompletionMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val tools: List<ChatCompletionTool> = emptyList(),
    val toolChoice: String? = null,
)

data class ChatCompletionMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ChatCompletionToolCall> = emptyList(),
    val imageUrls: List<String> = emptyList(),
)

data class ChatCompletionToolCall(
    val id: String,
    val type: String = "function",
    val function: ChatCompletionToolCallFunction,
)

data class ChatCompletionToolCallFunction(
    val name: String,
    val arguments: String,
)

data class ChatCompletionTool(
    val type: String = "function",
    val function: ChatCompletionFunction,
)

data class ChatCompletionFunction(
    val name: String,
    val description: String,
    val parametersJson: String,
)

data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChatCompletionChoice> = emptyList(),
    val usage: TokenUsage? = null,
)

data class ChatCompletionChoice(
    val delta: ChatCompletionDelta? = null,
    val message: ChatCompletionMessage? = null,
    val finishReason: String? = null,
)

data class ChatCompletionDelta(
    val role: String? = null,
    val content: String? = null,
    val toolCalls: List<ChatCompletionToolCallDelta> = emptyList(),
)

data class ChatCompletionToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val functionName: String? = null,
    val arguments: String = "",
)

data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)
