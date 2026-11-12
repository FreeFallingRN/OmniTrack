package com.omnitrack.app.data.repository

import android.util.Log
import com.omnitrack.app.data.local.MediaEntryDao
import com.omnitrack.app.data.remote.AuthorDto
import com.omnitrack.app.data.remote.EntryAuthorDto
import com.omnitrack.app.data.remote.MediaEntryDto
import com.omnitrack.app.data.remote.SeriesTitleDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.repository.SeriesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

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
            val entryAuthors = httpClient.get("$supabaseUrl/rest/v1/entry_authors?select=entry_id,author_id") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body<List<EntryAuthorDto>>()
            val authors = httpClient.get("$supabaseUrl/rest/v1/authors?select=id,name") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body<List<AuthorDto>>().associate { it.id to it.name }
            val authorNamesByEntry = entryAuthors
                .groupBy { it.entryId }
                .mapValues { (_, links) -> links.mapNotNull { authors[it.authorId] } }
            val seriesTitles = series.associate { it.id to it.title }
            mediaEntryDao.replaceAll(
                entries.map {
                    it.toEntity(
                        seriesTitle = seriesTitles[it.seriesId],
                        authorNames = authorNamesByEntry[it.id].orEmpty()
                    )
                }
            )
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to sync from Supabase", e)
        }
    }

    override suspend fun updateProgress(id: String, newProgress: Double) {
        val previousEntry = mediaEntryDao.getEntryById(id)
        mediaEntryDao.updateProgress(id, newProgress)
        if (supabaseUrl.isBlank()) return
        try {
            httpClient.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$id") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("current_unit" to newProgress))
            }

            httpClient.post(
                "$supabaseUrl/rest/v1/progress_history?on_conflict=entry_id,activity_date"
            ) {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "entry_id" to id,
                        "activity_date" to LocalDate.now().toString(),
                        "previous_unit" to (previousEntry?.currentUnit ?: newProgress),
                        "new_unit" to newProgress,
                        "source" to "android_app"
                    )
                )
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