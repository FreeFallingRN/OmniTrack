package com.omnitrack.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scrape_issue_reports")
data class ScrapeIssueReport(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val normalizedUrl: String,
    val adapterName: String,
    val scrapedSnapshotJson: String,
    val issueTagsJson: String,
    val note: String,
    val createdAt: String,
    val synced: Boolean = false,
    val deleted: Boolean = false
)

@Dao
interface ScrapeIssueReportDao {
    @Query("SELECT * FROM scrape_issue_reports WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeReports(): Flow<List<ScrapeIssueReport>>

    @Query("SELECT * FROM scrape_issue_reports WHERE id = :id")
    suspend fun getById(id: String): ScrapeIssueReport?

    @Query("SELECT id FROM scrape_issue_reports WHERE synced = 0 OR deleted = 1")
    suspend fun pendingIds(): List<String>

    @Insert
    suspend fun insert(report: ScrapeIssueReport)

    @Query("UPDATE scrape_issue_reports SET synced = 1 WHERE id = :id AND deleted = 0")
    suspend fun markSynced(id: String)

    @Query("UPDATE scrape_issue_reports SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM scrape_issue_reports WHERE id = :id AND deleted = 1")
    suspend fun removeDeleted(id: String)
}
