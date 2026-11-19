package com.omnitrack.app.domain.repository

import com.omnitrack.app.domain.model.DailyProgressStats
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun getMediaEntriesStream(): Flow<List<MediaEntry>>
    suspend fun fetchFromSupabase()
    suspend fun updateProgress(
        id: String,
        newProgress: Double,
        source: ProgressSource = ProgressSource.DETAIL_SHEET
    )
    suspend fun incrementProgress(
        id: String,
        delta: Double,
        source: ProgressSource = ProgressSource.INCREMENT
    )
    suspend fun deleteMediaEntry(id: String)

    fun getRecentActivityFeed(): Flow<List<ProgressHistoryItem>>
    fun getProgressStats(): Flow<OverallProgressStats>
    suspend fun getDailyStats(days: Int = 30): List<DailyProgressStats>
}