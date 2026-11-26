package com.omnitrack.app.domain.repository

import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.MediaEntryDto
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.data.model.UpdateEntryRequest
import com.omnitrack.app.data.model.UpdatePlatformRequest
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import kotlinx.coroutines.flow.Flow

class PartialMediaEntryCreateException(
    val entryId: String?,
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)

interface SeriesRepository {
    fun getMediaEntriesStream(): Flow<List<MediaEntry>>
    suspend fun fetchFromSupabase()
    suspend fun getSeries(): List<SeriesDto>
    suspend fun getAuthors(): List<AuthorDto>
    suspend fun getPlatforms(): List<PlatformDto>
    suspend fun createSeries(request: CreateSeriesRequest): SeriesDto
    suspend fun updateSeries(id: String, title: String, rating: Int?)
    suspend fun deleteSeries(id: String)
    suspend fun createAuthor(name: String, nativeName: String?, imageUrl: String?, bio: String?): AuthorDto
    suspend fun updateAuthor(id: String, name: String, nativeName: String?, imageUrl: String?, bio: String?)
    suspend fun deleteAuthor(id: String)
    suspend fun createPlatform(request: CreatePlatformRequest): PlatformDto
    suspend fun updatePlatform(id: String, request: UpdatePlatformRequest)
    suspend fun deletePlatform(id: String)
    suspend fun createMediaEntry(
        request: CreateEntryRequest,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ): MediaEntryDto
    suspend fun updateMediaEntry(
        id: String,
        request: UpdateEntryRequest,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    )
    suspend fun updateEntryStatus(id: String, status: String)
    suspend fun updateProgress(
        id: String,
        newProgress: Double,
        source: ProgressSource = ProgressSource.DETAIL_SHEET
    )
    suspend fun updateMoonReaderChapterIfHigher(id: String, newProgress: Double): Boolean
    suspend fun updateMangaBakaChapterIfHigher(
        id: String,
        newProgress: Double,
        completed: Boolean,
        seriesStatus: String? = null
    ): Boolean
    suspend fun incrementProgress(
        id: String,
        delta: Double,
        source: ProgressSource = ProgressSource.INCREMENT
    )
    suspend fun deleteMediaEntry(id: String)
    suspend fun updateMangaBakaUrl(id: String, sourceUrl: String)

    fun getRecentActivityFeed(): Flow<List<ProgressHistoryItem>>
    fun getProgressStats(): Flow<OverallProgressStats>
}