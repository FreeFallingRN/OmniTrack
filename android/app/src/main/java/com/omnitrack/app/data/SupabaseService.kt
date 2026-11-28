package com.omnitrack.app.data

import com.omnitrack.app.BuildConfig
import com.omnitrack.app.data.model.*
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
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.omnitrack.app.data.local.ScrapeIssueReport
import io.ktor.http.isSuccess
import com.omnitrack.app.domain.repository.PartialMediaEntryCreateException
import kotlinx.coroutines.CancellationException

object SupabaseService {

    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY

    private val client: HttpClient = HttpClient(Android) {
        expectSuccess = true
        followRedirects = false
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

    private suspend inline fun <reified T> getAllPages(path: String): List<T> {
        val result = mutableListOf<T>()
        val pageSize = 100
        do {
            val page: List<T> = client.get("$supabaseUrl/rest/v1/$path&limit=$pageSize&offset=${result.size}").body()
            result.addAll(page)
        } while (page.isNotEmpty())
        return result
    }

    suspend fun getSeries(): List<SeriesDto> {
        return client.get(
            "$supabaseUrl/rest/v1/series?select=id,title,clean_title,rating,created_at,updated_at&order=updated_at.desc"
        ).body()
    }

    suspend fun getSeriesTitles(): List<SeriesTitleDto> {
        return getAllPages("series?select=id,title&order=id.asc")
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
        return getAllPages(
            "media_entries?select=*,platform:platforms(id,name,website_url,logo_url)&order=sequence_number.asc,id.asc"
        )
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

    suspend fun updateEntryProgress(
        entryId: String,
        currentUnit: Double?,
        updatedStatus: String? = null
    ): MediaEntryDto {
        val payload = buildJsonObject {
            if (currentUnit != null) {
                put("current_unit", currentUnit)
            }
            if (updatedStatus != null) {
                put("user_status", updatedStatus)
            }
        }
        val list: List<MediaEntryDto> = client.patch(
            "$supabaseUrl/rest/v1/media_entries?id=eq.$entryId"
        ) {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(payload)
        }.body()
        return list.first()
    }

    suspend fun updateMangaBakaUrl(entryId: String, sourceUrl: String) {
        updateEntrySourceUrl(entryId, sourceUrl)
    }

    suspend fun updateEntrySourceUrl(entryId: String, sourceUrl: String) {
        val response = client.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$entryId") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(buildJsonObject { put("source_url", sourceUrl) })
        }
        check(response.status.isSuccess()) { "Could not update entry URL (HTTP ${response.status.value})" }
        val entries: List<MediaEntryDto> = response.body()
        check(entries.size == 1) { "Entry no longer exists or could not be updated" }
    }

    suspend fun backfillEntryCover(entryId: String, coverUrl: String): String? {
        repeat(3) {
            val entries: List<MediaEntryDto> = client.get("$supabaseUrl/rest/v1/media_entries") {
                url {
                    parameters.append("id", "eq.$entryId")
                    parameters.append("select", "*")
                }
            }.body()
            val current = entries.singleOrNull() ?: error("Entry no longer exists or could not be read")
            if (!current.coverUrl.isNullOrBlank()) return current.coverUrl
            val updated: List<MediaEntryDto> = client.patch("$supabaseUrl/rest/v1/media_entries") {
                url {
                    parameters.append("id", "eq.$entryId")
                    parameters.append("cover_url", current.coverUrl?.let { "eq.$it" } ?: "is.null")
                }
                contentType(ContentType.Application.Json)
                header("Prefer", "return=representation")
                setBody(buildJsonObject { put("cover_url", coverUrl) })
            }.body()
            if (updated.size == 1) return updated.single().coverUrl
            check(updated.isEmpty()) { "Could not update entry cover" }
        }
        error("Entry cover changed repeatedly; retry the sync")
    }

    suspend fun logProgress(
        entryId: String,
        activityDate: String,
        prevUnit: Double,
        newUnit: Double,
        source: String = "android_app",
    ): Boolean {
        val payload = buildJsonObject {
            put("entry_id", entryId)
            put("activity_date", activityDate)
            put("previous_unit", prevUnit)
            put("new_unit", newUnit)
            put("source", source)
        }
        val response = client.post(
            "$supabaseUrl/rest/v1/progress_history?on_conflict=entry_id,activity_date"
        ) {
            contentType(ContentType.Application.Json)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            setBody(payload)
        }
        return response.status.value in 200..299
    }

    suspend fun getAllProgressHistory(): List<ProgressHistoryDto> {
        return client.get(
            "$supabaseUrl/rest/v1/progress_history?select=*&order=activity_date.desc"
        ).body()
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
        val response = client.post("$supabaseUrl/rest/v1/media_entries") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(request)
        }
        return try {
            response.body<List<MediaEntryDto>>().single()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PartialMediaEntryCreateException(null, "Entry response could not be read", e)
        }
    }

    suspend fun getAuthors(): List<AuthorDto> =
        getAllPages("authors?select=*&order=name.asc,id.asc")

    suspend fun getEntryAuthors(entryId: String): List<EntryAuthorDto> =
        client.get("$supabaseUrl/rest/v1/entry_authors?entry_id=eq.$entryId&select=*").body()

    suspend fun getAllEntryAuthors(): List<EntryAuthorDto> =
        getAllPages("entry_authors?select=entry_id,author_id,role&order=entry_id.asc,author_id.asc,role.asc")

    suspend fun getAlternateTitles(): List<AlternateTitleDto> =
        getAllPages("alternate_titles?select=id,series_id,entry_id,title,clean_title,language,is_primary&order=id.asc")

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
        val updated: List<MediaEntryDto> = client.patch("$supabaseUrl/rest/v1/media_entries?id=eq.$id") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
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
        }.body()
        check(updated.size == 1) { "Entry no longer exists or could not be updated" }
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

    suspend fun uploadScrapeIssueReport(report: ScrapeIssueReport) {
        val payload = buildJsonObject {
            put("id", report.id)
            put("source_url", report.sourceUrl)
            put("normalized_url", report.normalizedUrl)
            put("adapter_name", report.adapterName)
            put("scraped_snapshot", Json.parseToJsonElement(report.scrapedSnapshotJson))
            put("issue_tags", Json.parseToJsonElement(report.issueTagsJson))
            put("note", report.note)
            put("created_at", report.createdAt)
            put("synced", true)
        }
        val response = client.post("$supabaseUrl/rest/v1/scrape_issue_reports?on_conflict=id") {
            contentType(ContentType.Application.Json)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            setBody(payload)
        }
        check(response.status.isSuccess()) { "Report upload failed: HTTP ${response.status.value}" }
    }

    suspend fun deleteScrapeIssueReport(id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/scrape_issue_reports?id=eq.$id")
        check(response.status.isSuccess()) { "Report deletion failed: HTTP ${response.status.value}" }
    }
}