package com.omnitrack.app.data

import com.omnitrack.app.BuildConfig
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.EntryAuthorDto
import com.omnitrack.app.data.model.ExternalLinkDto
import com.omnitrack.app.data.model.MediaEntryDto
import com.omnitrack.app.data.model.ProgressHistoryRequest
import com.omnitrack.app.data.model.SeriesDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object SupabaseService {

    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY

    private val client: HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        defaultRequest {
            header("apikey", supabaseAnonKey)
            header("Authorization", "Bearer $supabaseAnonKey")
        }
    }

    suspend fun getSeries(): List<SeriesDto> {
        return client.get(
            "$supabaseUrl/rest/v1/series?select=id,title,clean_title,user_status,publishing_status,current_cumulative_unit,total_units,unit_name,rating,notes,created_at,updated_at&order=updated_at.desc"
        ).body()
    }

    suspend fun getSeriesById(id: String): SeriesDto {
        val list: List<SeriesDto> = client.get(
            "$supabaseUrl/rest/v1/series?id=eq.$id&select=*&limit=1"
        ).body()
        return list.first()
    }

    suspend fun getMediaEntries(seriesId: String): List<MediaEntryDto> {
        return client.get(
            "$supabaseUrl/rest/v1/media_entries?series_id=eq.$seriesId&select=*&order=sequence_number.asc"
        ).body()
    }

    suspend fun getAllMediaEntries(): List<MediaEntryDto> {
        return client.get(
            "$supabaseUrl/rest/v1/media_entries?select=*&order=sequence_number.asc"
        ).body()
    }

    suspend fun getExternalLinks(seriesId: String): List<ExternalLinkDto> {
        return client.get(
            "$supabaseUrl/rest/v1/external_links?series_id=eq.$seriesId&select=*"
        ).body()
    }

    suspend fun updateSeriesProgress(seriesId: String, cumulativeUnit: Double): SeriesDto {
        val list: List<SeriesDto> = client.patch(
            "$supabaseUrl/rest/v1/series?id=eq.$seriesId"
        ) {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(mapOf("current_cumulative_unit" to cumulativeUnit))
        }.body()
        return list.first()
    }

    suspend fun updateEntryProgress(entryId: String, currentUnit: Double): MediaEntryDto {
        val list: List<MediaEntryDto> = client.patch(
            "$supabaseUrl/rest/v1/media_entries?id=eq.$entryId"
        ) {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(mapOf("current_unit" to currentUnit))
        }.body()
        return list.first()
    }

    suspend fun logProgress(
        entryId: String,
        activityDate: String,
        prevUnit: Double,
        newUnit: Double,
        source: String,
    ): Boolean {
        val request = ProgressHistoryRequest(
            entryId = entryId,
            activityDate = activityDate,
            previousUnit = prevUnit,
            newUnit = newUnit,
            source = source
        )
        val response = client.post(
            "$supabaseUrl/rest/v1/progress_history?on_conflict=entry_id,activity_date"
        ) {
            contentType(ContentType.Application.Json)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            setBody(request)
        }
        return response.status.value in 200..299
    }

    suspend fun createSeries(request: CreateSeriesRequest): SeriesDto {
        val list: List<SeriesDto> = client.post("$supabaseUrl/rest/v1/series") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(request)
        }.body()
        return list.first()
    }

    suspend fun createEntry(request: CreateEntryRequest): MediaEntryDto {
        val list: List<MediaEntryDto> = client.post("$supabaseUrl/rest/v1/media_entries") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(request)
        }.body()
        return list.first()
    }

    suspend fun getAuthors(): List<AuthorDto> =
        client.get("$supabaseUrl/rest/v1/authors?select=*&order=name.asc").body()

    suspend fun getEntryAuthors(entryId: String): List<EntryAuthorDto> =
        client.get("$supabaseUrl/rest/v1/entry_authors?entry_id=eq.$entryId&select=*").body()

    suspend fun createAuthor(
        name: String,
        nativeName: String? = null,
        imageUrl: String? = null,
        bio: String? = null
    ): AuthorDto {
        val list: List<AuthorDto> = client.post("$supabaseUrl/rest/v1/authors") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(
                mapOf(
                    "name" to name.trim(),
                    "native_name" to nativeName?.trim()?.ifBlank { null },
                    "image_url" to imageUrl?.trim()?.ifBlank { null },
                    "bio" to bio?.trim()?.ifBlank { null }
                )
            )
        }.body()
        return list.first()
    }

    suspend fun updateAuthor(
        id: String,
        name: String,
        nativeName: String? = null,
        imageUrl: String? = null,
        bio: String? = null
    ) {
        client.patch("$supabaseUrl/rest/v1/authors?id=eq.$id") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "name" to name.trim(),
                    "native_name" to nativeName?.trim()?.ifBlank { null },
                    "image_url" to imageUrl?.trim()?.ifBlank { null },
                    "bio" to bio?.trim()?.ifBlank { null }
                )
            )
        }
    }

    suspend fun deleteAuthor(id: String) {
        client.delete("$supabaseUrl/rest/v1/authors?id=eq.$id")
    }

    suspend fun updateSeries(
        id: String,
        title: String,
        userStatus: String,
        publishingStatus: String,
        currentCumulativeUnit: Double?,
        totalUnits: Double?,
        unitName: String,
        rating: Int?,
        notes: String?
    ) {
        client.patch("$supabaseUrl/rest/v1/series?id=eq.$id") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "title" to title.trim(),
                    "user_status" to userStatus,
                    "publishing_status" to publishingStatus,
                    "current_cumulative_unit" to currentCumulativeUnit,
                    "total_units" to totalUnits,
                    "unit_name" to unitName.trim(),
                    "rating" to rating,
                    "notes" to notes?.trim()?.ifBlank { null }
                )
            )
        }
    }

    suspend fun deleteSeries(id: String) {
        client.delete("$supabaseUrl/rest/v1/series?id=eq.$id")
    }

    suspend fun updateEntry(
        id: String,
        title: String,
        type: String,
        seriesId: String,
        authorId: String,
        coverUrl: String?,
        sourceUrl: String?,
        platform: String?,
        userStatus: String,
        publishingStatus: String,
        sequenceNumber: Double,
        currentUnit: Double,
        totalUnits: Double?,
        unitName: String,
        isCanon: Boolean
    ) {
        client.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$id") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "title" to title.trim(),
                    "type" to type,
                    "series_id" to seriesId,
                    "cover_url" to coverUrl?.trim()?.ifBlank { null },
                    "source_url" to sourceUrl?.trim()?.ifBlank { null },
                    "platform" to platform?.trim()?.ifBlank { null },
                    "user_status" to userStatus,
                    "publishing_status" to publishingStatus,
                    "sequence_number" to sequenceNumber,
                    "current_unit" to currentUnit,
                    "total_units" to totalUnits,
                    "unit_name" to unitName.trim(),
                    "is_canon" to isCanon
                )
            )
        }
        replaceEntryAuthor(id, authorId)
    }

    suspend fun deleteEntry(id: String) {
        client.delete("$supabaseUrl/rest/v1/media_entries?id=eq.$id")
    }

    suspend fun replaceEntryAuthor(entryId: String, authorId: String) {
        client.delete("$supabaseUrl/rest/v1/entry_authors?entry_id=eq.$entryId")
        client.post("$supabaseUrl/rest/v1/entry_authors") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("entry_id" to entryId, "author_id" to authorId, "role" to "author"))
        }
    }
}