package com.omnitrack.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class ProgressHistoryWithMedia(
    val entryId: String,
    val activityDate: String,
    val previousUnit: Double,
    val newUnit: Double,
    val source: String,
    val entryTitle: String?,
    val mediaType: String?,
    val unitName: String?,
    val coverUrl: String?
)

@Dao
interface ProgressHistoryDao {
    @Query("SELECT * FROM progress_history WHERE entryId = :entryId AND activityDate = :activityDate LIMIT 1")
    suspend fun getEntryHistoryForDate(entryId: String, activityDate: String): ProgressHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: ProgressHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(histories: List<ProgressHistoryEntity>)

    @Query("DELETE FROM progress_history")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(histories: List<ProgressHistoryEntity>) {
        deleteAll()
        insertAll(histories)
    }

    @Query("""
        SELECT 
            h.entryId AS entryId,
            h.activityDate AS activityDate,
            h.previousUnit AS previousUnit,
            h.newUnit AS newUnit,
            h.source AS source,
            m.title AS entryTitle,
            m.type AS mediaType,
            m.unitName AS unitName,
            m.coverUrl AS coverUrl
        FROM progress_history h
        LEFT JOIN media_entries m ON h.entryId = m.id
        ORDER BY h.activityDate DESC, h.newUnit DESC
    """)
    fun getRecentActivityFeed(): Flow<List<ProgressHistoryWithMedia>>

    @Query("DELETE FROM progress_history WHERE entryId = :entryId")
    suspend fun deleteHistoryForEntry(entryId: String)
}

