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
import kotlin.collections.filter
import com.omnitrack.app.data.remote.AlternateTitleDto
import com.omnitrack.app.domain.model.EntryCreator

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
            val series: List<SeriesTitleDto> = httpClient.get("$supabaseUrl/rest/v1/series?select=id,title") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body()

            val entries: List<MediaEntryDto> = httpClient.get("$supabaseUrl/rest/v1/media_entries?select=*") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body()

            val entryAuthors = httpClient.get("$supabaseUrl/rest/v1/entry_authors?select=entry_id,author_id,role") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body<List<EntryAuthorDto>>()

            val authors = httpClient.get("$supabaseUrl/rest/v1/authors?select=id,name") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body<List<AuthorDto>>().associate { it.id to it.name }

            val altTitles = httpClient.get("$supabaseUrl/rest/v1/alternate_titles?select=id,series_id,entry_id,title,clean_title,language,is_primary") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
            }.body<List<AlternateTitleDto>>()

            val creatorsByEntry = entryAuthors
                .groupBy { it.entryId }
                .mapValues { (_, links) ->
                    links.mapNotNull { link ->
                        authors[link.authorId]?.let { name ->
                            EntryCreator(id = link.authorId, name = name, role = link.role)
                        }
                    }
                }

            val altTitlesByEntry = altTitles.filter { it.entryId != null }.groupBy { it.entryId!! }
            val altTitlesBySeries = altTitles.filter { it.entryId == null && it.seriesId != null }.groupBy { it.seriesId!! }
            val seriesTitles = series.associate { it.id to it.title }

            mediaEntryDao.replaceAll(
                entries.map { entry ->
                    val mergedAlts = (
                            altTitlesByEntry[entry.id].orEmpty() +
                                    entry.seriesId?.let { altTitlesBySeries[it] }.orEmpty()
                            ).map { it.title }.distinct()

                    entry.toEntity(
                        seriesTitle = seriesTitles[entry.seriesId],
                        creators = creatorsByEntry[entry.id].orEmpty(),
                        altTitles = mergedAlts
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

    override suspend fun deleteMediaEntry(id: String) {
        mediaEntryDao.deleteEntryById(id)
    }
}