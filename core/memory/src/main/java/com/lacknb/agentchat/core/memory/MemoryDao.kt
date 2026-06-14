package com.lacknb.agentchat.core.memory

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MemoryDao {
    @Query("SELECT * FROM memory_items WHERE archived = 0 ORDER BY updated_at_millis DESC")
    fun observeActive(): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE archived = 0
        AND (:type IS NULL OR type = :type)
        AND (
            :query IS NULL
            OR title LIKE '%' || :query || '%'
            OR content LIKE '%' || :query || '%'
            OR tags_json LIKE '%' || :query || '%'
        )
        ORDER BY updated_at_millis DESC
        """,
    )
    fun observeSearch(query: String?, type: String?): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE archived = 0
        AND (:type IS NULL OR type = :type)
        AND (
            :query IS NULL
            OR title LIKE '%' || :query || '%'
            OR content LIKE '%' || :query || '%'
            OR tags_json LIKE '%' || :query || '%'
        )
        ORDER BY updated_at_millis DESC
        LIMIT :limit
        """,
    )
    fun search(query: String?, type: String?, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memory_items WHERE id = :id LIMIT 1")
    fun findById(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: MemoryEntity)

    @Query("UPDATE memory_items SET archived = 1, updated_at_millis = :updatedAtMillis WHERE id = :id")
    fun archive(id: String, updatedAtMillis: Long)

    @Delete
    fun delete(entity: MemoryEntity)
}
