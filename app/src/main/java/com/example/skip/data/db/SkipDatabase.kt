package com.example.skip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

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

        /**
         * Every schema bump must add its [Migration] here and commit the matching
         * `app/schemas/com.example.skip.data.db.SkipDatabase/<version>.json`.
         *
         * Deliberately no `fallbackToDestructiveMigration()`: silently dropping the
         * database would erase the user's click history.
         */
        private val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var instance: SkipDatabase? = null

        fun get(context: Context): SkipDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SkipDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
