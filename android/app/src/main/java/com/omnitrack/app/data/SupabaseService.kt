package com.omnitrack.app.data

import com.omnitrack.app.BuildConfig
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.UpdateSeriesRequest
import com.omnitrack.app.data.model.UpdateEntryRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.UpdatePlatformRequest
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
import com.omnitrack.app.data.remote.AlternateTitleDto

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
            "$supabaseUrl/rest/v1/series?select=id,title,clean_title,rating,created_at,updated_at&order=updated_at.desc"
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
            "$supabaseUrl/rest/v1/media_entries?series_id=eq.$seriesId&select=*,platform:platforms(id,name,website_url,logo_url)&order=sequence_number.asc"
        ).body()
    }

    suspend fun getAllMediaEntries(): List<MediaEntryDto> {
        return client.get(
            "$supabaseUrl/rest/v1/media_entries?select=*,platform:platforms(id,name,website_url,logo_url)&order=sequence_number.asc"
        ).body()
    }

    suspend fun getPlatforms(): List<PlatformDto> {
        return client.get("$supabaseUrl/rest/v1/platforms?select=*&order=name.asc").body()
    }

    suspend fun createPlatform(request: CreatePlatformRequest): PlatformDto {
        return client.post("$supabaseUrl/rest/v1/platforms") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(request)
        }.body<List<PlatformDto>>().first()
    }

    suspend fun updatePlatform(id: String, request: UpdatePlatformRequest) {
        client.patch("$supabaseUrl/rest/v1/platforms?id=eq.$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun deletePlatform(id: String) {
        client.delete("$supabaseUrl/rest/v1/platforms?id=eq.$id")
    }

    suspend fun getExternalLinks(seriesId: String): List<ExternalLinkDto> {
        return client.get(
            "$supabaseUrl/rest/v1/external_links?series_id=eq.$seriesId&select=*"
        ).body()
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
        rating: Int?,
    ) {
        client.patch("$supabaseUrl/rest/v1/series?id=eq.$id") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateSeriesRequest(
                    title = title.trim(),
                    rating = rating
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
        seriesId: String?,
        creators: List<Pair<String, String>>,
        platformId: String?,
        coverUrl: String?,
        sourceUrl: String?,
        userStatus: String,
        publishingStatus: String,
        sequenceNumber: Double?,
        currentUnit: Double,
        unitName: String,
        isCanon: Boolean,
        alternativeTitles: List<String> = emptyList()
    ) {
        client.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$id") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateEntryRequest(
                    title = title.trim(),
                    type = type,
                    seriesId = seriesId,
                    coverUrl = coverUrl?.trim()?.ifBlank { null },
                    sourceUrl = sourceUrl?.trim()?.ifBlank { null },
                    platformId = platformId,
                    userStatus = userStatus,
                    publishingStatus = publishingStatus,
                    sequenceNumber = sequenceNumber,
                    currentUnit = currentUnit,
                    unitName = unitName.trim(),
                    isCanon = isCanon
                )
            )
        }
        replaceEntryAuthors(id, creators)
        replaceEntryAlternateTitles(id, seriesId, alternativeTitles)
    }

    suspend fun deleteEntry(id: String) {
        client.delete("$supabaseUrl/rest/v1/media_entries?id=eq.$id")
    }

    suspend fun replaceEntryAuthors(entryId: String, creators: List<Pair<String, String>>) {
        client.delete("$supabaseUrl/rest/v1/entry_authors?entry_id=eq.$entryId")
        val rows = creators
            .distinctBy { (authorId, role) -> "$authorId::${role.trim().lowercase()}" }
            .map { (authorId, role) ->
                mapOf(
                    "entry_id" to entryId,
                    "author_id" to authorId,
                    "role" to role.trim().lowercase().ifBlank { "author" }
                )
            }
        if (rows.isNotEmpty()) {
            client.post("$supabaseUrl/rest/v1/entry_authors") {
                contentType(ContentType.Application.Json)
                setBody(rows)
            }
        }
    }

    suspend fun replaceEntryAlternateTitles(
        entryId: String,
        seriesId: String?,
        titles: List<String>
    ) {
        client.delete("$supabaseUrl/rest/v1/alternate_titles?entry_id=eq.$entryId")
        val rows = titles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map { title ->
                AlternateTitleDto(
                    seriesId = seriesId,
                    entryId = entryId,
                    title = title
                )
            }
        if (rows.isNotEmpty()) {
            client.post("$supabaseUrl/rest/v1/alternate_titles") {
                contentType(ContentType.Application.Json)
                setBody(rows)
            }
        }
    }
}