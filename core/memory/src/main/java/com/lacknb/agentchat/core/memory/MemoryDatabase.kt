package com.lacknb.agentchat.core.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MemoryEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "agentchat-memory.db",
                )
                    .addMigrations(Migration1To2)
                    .build()
                    .also { instance = it }
            }
        }

        private val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE memory_items_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        type TEXT NOT NULL,
                        tags_json TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sensitivity TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        created_at_millis INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL,
                        last_accessed_at_millis INTEGER,
                        archived INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO memory_items_new (
                        id,
                        title,
                        content,
                        type,
                        tags_json,
                        source,
                        sensitivity,
                        confidence,
                        created_at_millis,
                        updated_at_millis,
                        last_accessed_at_millis,
                        archived
                    )
                    SELECT
                        id,
                        title,
                        content ||
                            CASE
                                WHEN url IS NULL OR trim(url) = '' OR instr(content, url) > 0 THEN ''
                                ELSE char(10) || trim(url)
                            END,
                        CASE WHEN type = 'Url' THEN 'Note' ELSE type END,
                        tags_json,
                        source,
                        sensitivity,
                        confidence,
                        created_at_millis,
                        updated_at_millis,
                        last_accessed_at_millis,
                        archived
                    FROM memory_items
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE memory_items")
                database.execSQL("ALTER TABLE memory_items_new RENAME TO memory_items")
                database.execSQL("CREATE INDEX index_memory_items_type ON memory_items(type)")
                database.execSQL("CREATE INDEX index_memory_items_archived ON memory_items(archived)")
                database.execSQL("CREATE INDEX index_memory_items_updated_at_millis ON memory_items(updated_at_millis)")
            }
        }
    }
}
