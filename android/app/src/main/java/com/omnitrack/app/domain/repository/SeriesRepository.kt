package com.omnitrack.app.domain.repository

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
    suspend fun updateMoonReaderChapterIfHigher(id: String, newProgress: Double): Boolean
    suspend fun updateMangaBakaChapterIfHigher(
        id: String,
        newProgress: Double,
        completed: Boolean,
        seriesStatus: String? = null
    ): Boolean
    suspend fun incrementProgress(
        id: String,
        delta: Double,
        source: ProgressSource = ProgressSource.INCREMENT
    )
    suspend fun deleteMediaEntry(id: String)
    suspend fun createMangaBakaEntry(
        title: String,
        type: String,
        status: String,
        publishingStatus: String,
        progress: Double,
        coverUrl: String?,
        sourceUrl: String
    ): MediaEntry
    suspend fun updateMangaBakaUrl(id: String, sourceUrl: String)

    fun getRecentActivityFeed(): Flow<List<ProgressHistoryItem>>
    fun getProgressStats(): Flow<OverallProgressStats>
}