package com.lacknb.agentchat.core.memory

enum class MemoryType(val displayName: String) {
    Note("笔记"),
    Preference("偏好"),
    Fact("事实"),
}

enum class MemorySource {
    User,
    Agent,
}

enum class MemorySensitivity(val displayName: String) {
    Low("普通"),
    Medium("敏感"),
    High("高敏"),
}

enum class MemoryRetrievalMode {
    Keyword,
    Vector,
    Hybrid,
}

data class MemoryItem(
    val id: String,
    val title: String,
    val content: String,
    val type: MemoryType,
    val tags: List<String>,
    val source: MemorySource,
    val sensitivity: MemorySensitivity,
    val confidence: Float,
    val embedding: List<Float>,
    val embeddingModel: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastAccessedAtMillis: Long?,
    val archived: Boolean,
)
