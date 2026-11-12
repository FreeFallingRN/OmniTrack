package com.omnitrack.app.data.repository

import android.util.Log
import com.omnitrack.app.data.local.MediaEntryDao
import com.omnitrack.app.data.remote.MediaEntryDto
import com.omnitrack.app.data.remote.SeriesTitleDto
import com.omnitrack.app.domain.model.MediaEntry
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
    private val mediaEntryDao: MediaEntryDao,
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val supabaseKey: String
) : SeriesRepository {

    override fun getMediaEntriesStream(): Flow<List<MediaEntry>> {
        return mediaEntryDao.getAllEntries().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun fetchFromSupabase() {
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) return
        try {
            val seriesResponse = httpClient.get("$supabaseUrl/rest/v1/series?select=id,title") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }
            val series: List<SeriesTitleDto> = seriesResponse.body()
            val entriesResponse = httpClient.get("$supabaseUrl/rest/v1/media_entries?select=*") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }
            val entries: List<MediaEntryDto> = entriesResponse.body()
            val seriesTitles = series.associate { it.id to it.title }
            mediaEntryDao.insertAll(entries.map { it.toEntity(seriesTitles[it.seriesId]) })
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to sync from Supabase", e)
        }
    }

    override suspend fun updateProgress(id: String, newProgress: Double) {
        mediaEntryDao.updateProgress(id, newProgress)
        if (supabaseUrl.isBlank()) return
        try {
            httpClient.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$id") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("current_unit" to newProgress))
            }
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to update progress on Supabase", e)
        }
    }

    override suspend fun setActiveTarget(id: String, isActive: Boolean) {
        mediaEntryDao.updateActiveTargetState(id, isActive)
        if (supabaseUrl.isBlank()) return
        try {
            httpClient.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$id") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("is_active_target" to isActive))
            }
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to update active target on Supabase", e)
        }
    }

    override suspend fun deleteMediaEntry(id: String) {
        mediaEntryDao.deleteEntryById(id)
    }
}