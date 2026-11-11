package com.omnitrack.app.domain.repository

import com.omnitrack.app.domain.model.Series
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun getSeriesStream(): Flow<List<Series>>
    suspend fun fetchFromSupabase()
    suspend fun updateProgress(id: String, newProgress: Double)
    suspend fun setActiveTarget(id: String, isActive: Boolean)
    suspend fun deleteSeries(id: String)
}