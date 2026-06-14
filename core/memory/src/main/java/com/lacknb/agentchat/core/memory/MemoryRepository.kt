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

    suspend fun searchMemories(
        query: String,
        type: MemoryType? = null,
        limit: Int = 20,
    ): List<MemoryItem> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().takeIf { it.isNotBlank() }
        val resultLimit = limit.coerceIn(1, 100)
        if (normalizedQuery == null) {
            return@withContext dao.search(query = null, type = type?.name, limit = resultLimit)
                .map { it.toMemoryItem() }
        }

        val queryTokens = normalizedQuery.searchTokens()
        val candidates = dao.search(query = null, type = type?.name, limit = 200)
            .map { it.toMemoryItem() }
        candidates
            .mapNotNull { memory ->
                val score = memory.matchScore(normalizedQuery, queryTokens)
                if (score > 0) memory to score else null
            }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAtMillis },
            )
            .take(resultLimit)
            .map { it.first }
    }

    suspend fun createMemory(
        title: String,
        content: String,
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
            title = title.normalizedTitle(normalizedContent),
            content = normalizedContent,
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
        type: MemoryType,
        tags: List<String>,
        sensitivity: MemorySensitivity,
    ): MemoryItem = withContext(Dispatchers.IO) {
        val current = dao.findById(id)?.toMemoryItem() ?: error("记忆不存在：$id")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "记忆内容不能为空" }

        val updated = current.copy(
            title = title.normalizedTitle(normalizedContent),
            content = normalizedContent,
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

    private fun String.normalizedTitle(content: String): String {
        val title = trim()
        if (title.isNotBlank()) return title
        return content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32) ?: "未命名记忆"
    }

    private fun newMemoryId(): String {
        return "memory-${System.currentTimeMillis()}-${(1000..9999).random()}"
    }

    private fun String.searchTokens(): List<String> {
        return lowercase()
            .split(Regex("""[\s,，。.;；:：/\\|#]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun MemoryItem.matchScore(query: String, tokens: List<String>): Int {
        val searchableText = buildString {
            append(title)
            append('\n')
            append(content)
            append('\n')
            append(tags.joinToString(" "))
            append('\n')
            append(type.displayName)
        }.lowercase()
        val normalizedQuery = query.lowercase()
        var score = 0
        if (searchableText.contains(normalizedQuery)) score += 8
        tokens.forEach { token ->
            if (searchableText.contains(token)) score += 3
        }
        return score
    }
}
