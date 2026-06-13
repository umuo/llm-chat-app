package com.lacknb.agentchat.core.model

enum class ChatMode {
    Chat,
    Agent,
}

enum class AgentPolicyType {
    ReAct,
    Planning,
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val status: MessageStatus = MessageStatus.Complete,
)

data class ChatToolCall(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String = "",
)

enum class MessageRole {
    User,
    Assistant,
    Tool,
    System,
}

enum class MessageStatus {
    Streaming,
    Complete,
    Failed,
}
