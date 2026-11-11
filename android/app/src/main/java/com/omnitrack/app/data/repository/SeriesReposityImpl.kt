package com.omnitrack.app.data.repository

import android.util.Log
import com.omnitrack.app.data.local.SeriesDao
import com.omnitrack.app.data.remote.SeriesDto
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.repository.SeriesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SeriesRepositoryImpl(
    private val seriesDao: SeriesDao,
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val supabaseKey: String
) : SeriesRepository {

    override fun getSeriesStream(): Flow<List<Series>> {
        return seriesDao.getAllSeries().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun fetchFromSupabase() {
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) return
        try {
            val response = httpClient.get("$supabaseUrl/rest/v1/series?select=*") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }
            val dtos: List<SeriesDto> = response.body()
            val entities = dtos.map { it.toDomain().toEntity() }
            seriesDao.insertAll(entities)
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to sync from Supabase", e)
        }
    }

    override suspend fun updateProgress(id: String, newProgress: Double) {
        seriesDao.updateProgress(id, newProgress)
        if (supabaseUrl.isBlank()) return
        try {
            httpClient.patch("$supabaseUrl/rest/v1/series?id=eq.$id") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("current_cumulative_unit" to newProgress))
            }
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to update progress on Supabase", e)
        }
    }

    override suspend fun setActiveTarget(id: String, isActive: Boolean) {
        seriesDao.updateActiveTargetState(id, isActive)
        if (supabaseUrl.isBlank()) return
        try {
            httpClient.patch("$supabaseUrl/rest/v1/series?id=eq.$id") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("is_active_target" to isActive))
            }
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to update active target on Supabase", e)
        }
    }

    override suspend fun deleteSeries(id: String) {
        seriesDao.deleteSeriesById(id)
    }
}