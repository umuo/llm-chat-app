package com.lacknb.agentchat.core.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MemoryRepository(context: Context) {
    private val dao = MemoryDatabase.getInstance(context).memoryDao()

    val memories: Flow<List<MemoryItem>> = dao.observeActive().map { entities ->
        entities.map { it.toMemoryItem() }
    }

    fun observeSearch(
        query: String,
        type: MemoryType?,
    ): Flow<List<MemoryItem>> {
        val normalizedQuery = query.trim().takeIf { it.isNotBlank() }
        return dao.observeSearch(normalizedQuery, type?.name).map { entities ->
            entities.map { it.toMemoryItem() }
        }
    }

    suspend fun createMemory(
        title: String,
        content: String,
        url: String?,
        type: MemoryType,
        tags: List<String>,
        sensitivity: MemorySensitivity = MemorySensitivity.Low,
        source: MemorySource = MemorySource.User,
        confidence: Float = 1f,
    ): MemoryItem = withContext(Dispatchers.IO) {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "记忆内容不能为空" }

        val now = System.currentTimeMillis()
        val item = MemoryItem(
            id = newMemoryId(),
            title = title.normalizedTitle(normalizedContent, url),
            content = normalizedContent,
            url = url?.trim()?.takeIf { it.isNotBlank() },
            type = type,
            tags = tags.distinctTags(),
            source = source,
            sensitivity = sensitivity,
            confidence = confidence.coerceIn(0f, 1f),
            createdAtMillis = now,
            updatedAtMillis = now,
            lastAccessedAtMillis = null,
            archived = false,
        )
        dao.upsert(item.toEntity())
        item
    }

    suspend fun updateMemory(
        id: String,
        title: String,
        content: String,
        url: String?,
        type: MemoryType,
        tags: List<String>,
        sensitivity: MemorySensitivity,
    ): MemoryItem = withContext(Dispatchers.IO) {
        val current = dao.findById(id)?.toMemoryItem() ?: error("记忆不存在：$id")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "记忆内容不能为空" }

        val updated = current.copy(
            title = title.normalizedTitle(normalizedContent, url),
            content = normalizedContent,
            url = url?.trim()?.takeIf { it.isNotBlank() },
            type = type,
            tags = tags.distinctTags(),
            sensitivity = sensitivity,
            updatedAtMillis = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        updated
    }

    suspend fun archiveMemory(id: String) = withContext(Dispatchers.IO) {
        dao.archive(id, System.currentTimeMillis())
    }

    suspend fun deleteMemory(id: String) = withContext(Dispatchers.IO) {
        val entity = dao.findById(id) ?: return@withContext
        dao.delete(entity)
    }

    private fun String.normalizedTitle(content: String, url: String?): String {
        val title = trim()
        if (title.isNotBlank()) return title
        val urlTitle = url?.trim()?.removePrefix("https://")?.removePrefix("http://")?.takeIf { it.isNotBlank() }
        return urlTitle ?: content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32) ?: "未命名记忆"
    }

    private fun newMemoryId(): String {
        return "memory-${System.currentTimeMillis()}-${(1000..9999).random()}"
    }
}
