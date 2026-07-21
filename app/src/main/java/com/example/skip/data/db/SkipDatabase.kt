package com.example.skip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ClickLogEntity::class,
        ClickLogThrottleCountEntity::class,
        StorageMetadataEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SkipDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        private const val DATABASE_NAME = "skip_logs.db"

        @Volatile
        private var instance: SkipDatabase? = null

        fun get(context: Context): SkipDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SkipDatabase::class.java,
                    DATABASE_NAME
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
        }

        internal fun resetForTest() {
            instance?.close()
            instance = null
        }
    }
}
