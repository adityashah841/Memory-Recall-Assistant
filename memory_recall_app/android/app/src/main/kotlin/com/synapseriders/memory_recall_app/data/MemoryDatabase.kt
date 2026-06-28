package com.synapseriders.memory_recall_app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database over the two-table knowledge graph (Section 5). Room enables SQLite foreign
 * key enforcement by default, so the `ON DELETE SET NULL` relation on `session_table.person_id`
 * is honored.
 */
@Database(
    entities = [PersonProfileEntity::class, MemorySessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "memory_recall.db",
                ).build().also { INSTANCE = it }
            }
    }
}
