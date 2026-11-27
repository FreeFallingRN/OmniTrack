package com.omnitrack.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "pending_mutations", primaryKeys = ["entryId", "kind", "activityDate"])
data class PendingMutation(
    val entryId: String,
    val kind: String,
    val activityDate: String = "",
    val userStatus: String? = null
)

@Dao
interface PendingMutationDao {
    @Query("SELECT * FROM pending_mutations ORDER BY CASE kind WHEN 'delete' THEN 0 WHEN 'progress' THEN 1 ELSE 2 END, activityDate")
    suspend fun getAll(): List<PendingMutation>

    @Query("SELECT * FROM pending_mutations WHERE entryId = :entryId AND kind = :kind AND activityDate = :activityDate LIMIT 1")
    suspend fun get(entryId: String, kind: String, activityDate: String): PendingMutation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mutation: PendingMutation)

    @Query("DELETE FROM pending_mutations WHERE entryId = :entryId")
    suspend fun deleteForEntry(entryId: String)

    @Query("DELETE FROM pending_mutations WHERE entryId = :entryId AND kind = :kind AND activityDate = :activityDate")
    suspend fun delete(entryId: String, kind: String, activityDate: String)
}

@Database(
    entities = [MediaEntryEntity::class, ProgressHistoryEntity::class, ScrapeIssueReport::class, PendingMutation::class],
    version = 26,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaEntryDao(): MediaEntryDao
    abstract fun progressHistoryDao(): ProgressHistoryDao
    abstract fun scrapeIssueReportDao(): ScrapeIssueReportDao
    abstract fun pendingMutationDao(): PendingMutationDao

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

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scrape_issue_reports` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `sourceUrl` TEXT NOT NULL,
                        `normalizedUrl` TEXT NOT NULL,
                        `adapterName` TEXT NOT NULL,
                        `scrapedSnapshotJson` TEXT NOT NULL,
                        `issueTagsJson` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `synced` INTEGER NOT NULL,
                        `deleted` INTEGER NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pending_mutations` (
                        `entryId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `activityDate` TEXT NOT NULL,
                        `userStatus` TEXT,
                        PRIMARY KEY(`entryId`, `kind`, `activityDate`)
                    )
                """)
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_entries_seriesId_type_sequenceNumber` " +
                        "ON `media_entries` (`seriesId`, `type`, `sequenceNumber`)"
                )
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_entries` ADD COLUMN `seriesAlternativeTitles` TEXT")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_entries` ADD COLUMN `totalEpisodes` INTEGER")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "omnitrack_database"
                )
                    .addMigrations(
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26
                    )
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}