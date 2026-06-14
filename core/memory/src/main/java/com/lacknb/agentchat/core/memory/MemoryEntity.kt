package com.lacknb.agentchat.core.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

@Entity(
    tableName = "memory_items",
    indices = [
        Index("type"),
        Index("archived"),
        Index("updated_at_millis"),
    ],
)
internal data class MemoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val type: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    val source: String,
    val sensitivity: String,
    val confidence: Float,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "last_accessed_at_millis") val lastAccessedAtMillis: Long?,
    val archived: Boolean,
)

internal fun MemoryEntity.toMemoryItem(): MemoryItem {
    return MemoryItem(
        id = id,
        title = title,
        content = content,
        type = enumValueOrDefault(type, MemoryType.Note),
        tags = parseTags(tagsJson),
        source = enumValueOrDefault(source, MemorySource.User),
        sensitivity = enumValueOrDefault(sensitivity, MemorySensitivity.Low),
        confidence = confidence,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        lastAccessedAtMillis = lastAccessedAtMillis,
        archived = archived,
    )
}

internal fun MemoryItem.toEntity(): MemoryEntity {
    return MemoryEntity(
        id = id,
        title = title,
        content = content,
        type = type.name,
        tagsJson = tags.toTagsJson(),
        source = source.name,
        sensitivity = sensitivity.name,
        confidence = confidence,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        lastAccessedAtMillis = lastAccessedAtMillis,
        archived = archived,
    )
}

internal fun List<String>.toTagsJson(): String {
    return JSONArray().apply {
        distinctTags().forEach { put(it) }
    }.toString()
}

internal fun parseTags(json: String): List<String> {
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val tag = array.optString(index).trim()
                if (tag.isNotBlank()) add(tag)
            }
        }.distinctTags()
    }.getOrDefault(emptyList())
}

internal fun List<String>.distinctTags(): List<String> {
    return map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
    return enumValues<T>().firstOrNull { it.name == value } ?: default
}
