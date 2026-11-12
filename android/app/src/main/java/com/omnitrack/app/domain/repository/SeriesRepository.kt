package com.omnitrack.app.domain.repository

import com.omnitrack.app.domain.model.MediaEntry
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun getMediaEntriesStream(): Flow<List<MediaEntry>>
    suspend fun fetchFromSupabase()
    suspend fun updateProgress(id: String, newProgress: Double)
    suspend fun deleteMediaEntry(id: String)
}