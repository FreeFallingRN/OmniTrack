package com.omnitrack.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaEntryEntity::class, ProgressHistoryEntity::class],
    version = 21,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaEntryDao(): MediaEntryDao
    abstract fun progressHistoryDao(): ProgressHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `progress_history` (
                        `entryId` TEXT NOT NULL,
                        `activityDate` TEXT NOT NULL,
                        `previousUnit` REAL NOT NULL,
                        `newUnit` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`entryId`, `activityDate`)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_history_activityDate` ON `progress_history` (`activityDate`)")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_entries` ADD COLUMN `createdAt` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "omnitrack_database"
                )
                    .addMigrations(MIGRATION_19_20, MIGRATION_20_21)
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}