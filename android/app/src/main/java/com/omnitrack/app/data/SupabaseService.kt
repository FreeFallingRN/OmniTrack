package com.omnitrack.app.data

import com.omnitrack.app.BuildConfig
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
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
        seriesId: String,
        entryId: String?,
        prevUnit: Double,
        newUnit: Double,
        prevCumulative: Double?,
        newCumulative: Double?,
        source: String,
    ): Boolean {
        val request = ProgressHistoryRequest(
            seriesId = seriesId,
            entryId = entryId,
            previousUnit = prevUnit,
            newUnit = newUnit,
            previousCumulativeUnit = prevCumulative,
            newCumulativeUnit = newCumulative,
            source = source
        )
        val response = client.post("$supabaseUrl/rest/v1/progress_history") {
            contentType(ContentType.Application.Json)
            header("Prefer", "return=minimal")
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
}