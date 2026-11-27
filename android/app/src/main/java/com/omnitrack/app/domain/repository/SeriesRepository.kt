package com.omnitrack.app.domain.repository

import com.omnitrack.app.domain.model.Author
import com.omnitrack.app.domain.model.CreateEntryCommand
import com.omnitrack.app.domain.model.CreatePlatformCommand
import com.omnitrack.app.domain.model.CreateSeriesCommand
import com.omnitrack.app.domain.model.CreatedMediaEntry
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.Platform
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.model.UpdateEntryCommand
import com.omnitrack.app.domain.model.UpdatePlatformCommand
import kotlinx.coroutines.flow.Flow

class PartialMediaEntryCreateException(
    val entryId: String?,
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)

interface SeriesRepository {
    fun getMediaEntriesStream(): Flow<List<MediaEntry>>
    suspend fun fetchFromSupabase()
    suspend fun retryPendingLibraryMutations()
    suspend fun getSeries(): List<Series>
    suspend fun getAuthors(): List<Author>
    suspend fun getPlatforms(): List<Platform>
    suspend fun createSeries(request: CreateSeriesCommand): Series
    suspend fun updateSeries(id: String, title: String, rating: Int?)
    suspend fun deleteSeries(id: String)
    suspend fun createAuthor(name: String, nativeName: String?, imageUrl: String?, bio: String?): Author
    suspend fun updateAuthor(id: String, name: String, nativeName: String?, imageUrl: String?, bio: String?)
    suspend fun deleteAuthor(id: String)
    suspend fun createPlatform(request: CreatePlatformCommand): Platform
    suspend fun updatePlatform(id: String, request: UpdatePlatformCommand)
    suspend fun deletePlatform(id: String)
    suspend fun createMediaEntry(
        request: CreateEntryCommand,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ): CreatedMediaEntry
    suspend fun updateMediaEntry(
        id: String,
        request: UpdateEntryCommand,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ): Boolean
    suspend fun updateEntryStatus(id: String, status: String)
    suspend fun pendingTrackerStatus(id: String, service: String, linkKey: String): String?
    suspend fun clearPendingTrackerStatus(id: String, service: String, linkKey: String, status: String)
    suspend fun resolvePendingTrackerStatuses(service: String, links: Map<String, List<String>>)
    suspend fun updateProgress(
        id: String,
        newProgress: Double,
        source: ProgressSource = ProgressSource.DETAIL_SHEET
    )
    suspend fun updateMoonReaderChapterIfHigher(id: String, newProgress: Double): Boolean
    suspend fun updateMoonReaderProgress(id: String, newProgress: Double, source: ProgressSource)
    suspend fun updateAniListEpisodeIfHigher(
        id: String,
        newProgress: Double,
        initial: Boolean,
        status: String? = null
    ): Boolean
    suspend fun updateAniListMetadata(id: String, coverUrl: String?, totalEpisodes: Int?): Boolean
    suspend fun updateAniListUrl(id: String, sourceUrl: String): Boolean
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