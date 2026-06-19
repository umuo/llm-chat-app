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
    val imageUrls: List<String> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
)

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val isImage: Boolean,
    val base64Data: String? = null,
    val textContent: String? = null,
)

data class ChatToolCall(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String = "",
)

data class ChatContextSummary(
    val summary: String,
    val summarizedThroughMessageId: String?,
    val tokensBefore: Int,
    val updatedAtMillis: Long = System.currentTimeMillis(),
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
