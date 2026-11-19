package com.omnitrack.app.domain.repository

import com.omnitrack.app.domain.model.DailyProgressStats
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun getMediaEntriesStream(): Flow<List<MediaEntry>>
    suspend fun fetchFromSupabase()
    suspend fun updateProgress(id: String, newProgress: Double)
    suspend fun incrementProgress(id: String, delta: Double)
    suspend fun deleteMediaEntry(id: String)

    fun getRecentActivityFeed(): Flow<List<ProgressHistoryItem>>
    fun getProgressStats(): Flow<OverallProgressStats>
    suspend fun getDailyStats(days: Int = 30): List<DailyProgressStats>
}