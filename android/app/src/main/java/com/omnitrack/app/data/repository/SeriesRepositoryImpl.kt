package com.omnitrack.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.*
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.local.MediaEntryDao
import com.omnitrack.app.data.local.MediaEntryEntity
import com.omnitrack.app.data.local.PendingMutation
import com.omnitrack.app.data.local.ProgressHistoryDao
import com.omnitrack.app.data.local.ProgressHistoryEntity
import com.omnitrack.app.domain.model.*
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

class SeriesRepositoryImpl(
    private val database: AppDatabase,
    private val mediaEntryDao: MediaEntryDao,
    private val progressHistoryDao: ProgressHistoryDao,
    private val mangaBakaPreferences: MangaBakaPreferences,
    private val supabaseService: SupabaseService = SupabaseService
) : SeriesRepository {

    // Keep refreshes and progress uploads ordered with local progress and history writes.
    companion object {
        private val progressMutex = Mutex()
    }

    private suspend fun retryPendingMutations() {
        val pendingDao = database.pendingMutationDao()
        for (mutation in pendingDao.getAll()) {
            when (mutation.kind) {
                "delete" -> supabaseService.deleteEntry(mutation.entryId)
                "progress" -> {
                    val entry = checkNotNull(mediaEntryDao.getEntryById(mutation.entryId)) {
                        "Pending progress entry ${mutation.entryId} is missing"
                    }
                    supabaseService.updateEntryProgress(entry.id, entry.currentUnit, mutation.userStatus)
                }
                "history" -> {
                    val history = checkNotNull(
                        progressHistoryDao.getEntryHistoryForDate(mutation.entryId, mutation.activityDate)
                    ) { "Pending history for ${mutation.entryId} on ${mutation.activityDate} is missing" }
                    check(supabaseService.logProgress(
                        history.entryId, history.activityDate, history.previousUnit, history.newUnit, history.source
                    )) { "Supabase rejected progress history for ${history.entryId} on ${history.activityDate}" }
                }
                else -> error("Unknown pending mutation: ${mutation.kind}")
            }
            pendingDao.delete(mutation.entryId, mutation.kind, mutation.activityDate)
        }
    }

    override fun getMediaEntriesStream(): Flow<List<MediaEntry>> {
        return mediaEntryDao.getAllEntries().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    private suspend fun <T> mutateLibrary(action: suspend () -> T): T = progressMutex.withLock {
        try {
            retryPendingMutations()
            val result = action()
            refreshFromSupabaseLocked()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to mutate library in Supabase", e)
            throw e
        }
    }

    override suspend fun getSeries(): List<SeriesDto> = progressMutex.withLock { supabaseService.getSeries() }

    override suspend fun getAuthors(): List<AuthorDto> = progressMutex.withLock { supabaseService.getAuthors() }

    override suspend fun getPlatforms(): List<PlatformDto> = progressMutex.withLock { supabaseService.getPlatforms() }

    override suspend fun createSeries(request: CreateSeriesRequest): SeriesDto =
        mutateLibrary { supabaseService.createSeries(request) }

    override suspend fun updateSeries(id: String, title: String, rating: Int?) =
        mutateLibrary { supabaseService.updateSeries(id, title, rating) }

    override suspend fun deleteSeries(id: String) = mutateLibrary { supabaseService.deleteSeries(id) }

    override suspend fun createAuthor(
        name: String,
        nativeName: String?,
        imageUrl: String?,
        bio: String?
    ): AuthorDto = mutateLibrary { supabaseService.createAuthor(name, nativeName, imageUrl, bio) }

    override suspend fun updateAuthor(
        id: String,
        name: String,
        nativeName: String?,
        imageUrl: String?,
        bio: String?
    ) = mutateLibrary { supabaseService.updateAuthor(id, name, nativeName, imageUrl, bio) }

    override suspend fun deleteAuthor(id: String) = mutateLibrary { supabaseService.deleteAuthor(id) }

    override suspend fun createPlatform(request: CreatePlatformRequest): PlatformDto =
        mutateLibrary { supabaseService.createPlatform(request) }

    override suspend fun updatePlatform(id: String, request: UpdatePlatformRequest) =
        mutateLibrary { supabaseService.updatePlatform(id, request) }

    override suspend fun deletePlatform(id: String) = mutateLibrary { supabaseService.deletePlatform(id) }

    override suspend fun createMediaEntry(
        request: CreateEntryRequest,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ): MediaEntryDto = mutateLibrary {
        val created = supabaseService.createEntry(request)
        supabaseService.replaceEntryAuthors(created.id, creators)
        supabaseService.replaceEntryAlternateTitles(created.id, request.seriesId, alternativeTitles)
        created
    }

    override suspend fun updateMediaEntry(
        id: String,
        request: UpdateEntryRequest,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ) {
        var progressChanged = false
        mutateLibrary {
            val current = mediaEntryDao.getEntryById(id)
            progressChanged = current != null && current.currentUnit != request.currentUnit
            supabaseService.updateEntry(
                id = id,
                title = request.title,
                type = request.type,
                seriesId = request.seriesId,
                creators = creators,
                platformId = request.platformId,
                coverUrl = request.coverUrl,
                sourceUrl = request.sourceUrl,
                userStatus = request.userStatus,
                publishingStatus = request.publishingStatus,
                sequenceNumber = request.sequenceNumber,
                currentUnit = current?.currentUnit ?: request.currentUnit,
                unitName = request.unitName,
                isCanon = request.isCanon,
                alternativeTitles = alternativeTitles
            )
        }
        if (progressChanged) updateProgress(id, request.currentUnit, ProgressSource.EDITOR)
    }

    override suspend fun updateEntryStatus(id: String, status: String): Unit = mutateLibrary {
        supabaseService.updateEntryProgress(id, null, status)
    }

    override suspend fun updateMangaBakaUrl(id: String, sourceUrl: String): Unit = progressMutex.withLock {
        val entry = mediaEntryDao.getEntryById(id) ?: error("Entry no longer exists")
        supabaseService.updateMangaBakaUrl(id, sourceUrl)
        mediaEntryDao.insertAll(listOf(entry.copy(sourceUrl = sourceUrl)))
    }

    private suspend fun refreshFromSupabaseLocked() {
        val series = supabaseService.getSeriesTitles()
        val entries = supabaseService.getAllMediaEntries()
        val entryAuthors = supabaseService.getAllEntryAuthors()
        val authors = supabaseService.getAuthors().associate { it.id to it.name }
        val altTitles = supabaseService.getAlternateTitles()
        val history = supabaseService.getAllProgressHistory()

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

        val entrySnapshot = entries.map { entry ->
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
        val historySnapshot = history.map { dto ->
            ProgressHistoryEntity(
                entryId = dto.entryId,
                activityDate = dto.activityDate,
                previousUnit = dto.previousUnit,
                newUnit = dto.newUnit,
                source = dto.source,
                updatedAt = dto.updatedAt.orEmpty()
            )
        }
        database.withTransaction {
            mediaEntryDao.replaceAll(entrySnapshot)
            progressHistoryDao.replaceAll(historySnapshot)
        }
    }

    override suspend fun fetchFromSupabase(): Unit = progressMutex.withLock {
        try {
            retryPendingMutations()
            refreshFromSupabaseLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to sync from Supabase", e)
            throw e
        }
    }

    // MangaBaka uploads first so a failed Supabase write can be retried without losing the delta.
    private suspend fun recordProgressInternal(
        id: String,
        computeTarget: (current: MediaEntryEntity?) -> Pair<Double, String?>?,
        source: ProgressSource,
        uploadFirst: Boolean = false
    ): Boolean {
        return progressMutex.withLock {
            val today = LocalDate.now().toString()
            val current = mediaEntryDao.getEntryById(id)
            val (nextUnit, targetStatus) = computeTarget(current) ?: return@withLock false
            checkNotNull(current) { "Entry $id no longer exists" }

            var prevUnitForHistory = current.currentUnit
            val hasNetProgress = (nextUnit - prevUnitForHistory) > 0.0001

            if (source.isActiveConsumption && hasNetProgress) {
                val existingTodayHistory = progressHistoryDao.getEntryHistoryForDate(id, today)
                prevUnitForHistory = existingTodayHistory?.previousUnit
                    ?: current.currentUnit
            }

            suspend fun uploadHistory() {
                if (source.isActiveConsumption && hasNetProgress) {
                    check(
                        supabaseService.logProgress(
                            entryId = id,
                            activityDate = today,
                            prevUnit = prevUnitForHistory,
                            newUnit = nextUnit,
                            source = source.value
                        )
                    ) { "Supabase rejected progress history for $id on $today" }
                }
            }

            suspend fun uploadEntry() {
                supabaseService.updateEntryProgress(
                    entryId = id,
                    currentUnit = nextUnit,
                    updatedStatus = targetStatus?.takeIf { it != current.userStatus }
                )
            }

            if (uploadFirst) {
                retryPendingMutations()
                uploadHistory()
                uploadEntry()
            }

            database.withTransaction {
                if (targetStatus != null && targetStatus != current.userStatus) {
                    mediaEntryDao.updateProgressAndStatus(id, nextUnit, targetStatus)
                } else {
                    mediaEntryDao.updateProgress(id, nextUnit)
                }
                if (!uploadFirst) {
                    val pendingDao = database.pendingMutationDao()
                    val pendingStatus = pendingDao.get(id, "progress", "")?.userStatus
                    pendingDao.upsert(
                        PendingMutation(
                            id, "progress", userStatus = targetStatus?.takeIf { it != current.userStatus }
                                ?: pendingStatus
                        )
                    )
                }
                if (source.isActiveConsumption && hasNetProgress) {
                    progressHistoryDao.upsert(
                        ProgressHistoryEntity(
                            entryId = id, activityDate = today, previousUnit = prevUnitForHistory,
                            newUnit = nextUnit, source = source.value
                        )
                    )
                    if (!uploadFirst) {
                        database.pendingMutationDao().upsert(PendingMutation(id, "history", today))
                    }
                }
            }

            if (uploadFirst) return@withLock true
            try {
                retryPendingMutations()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SeriesRepository", "Failed to sync pending progress to Supabase", e)
            }
            true
        }
    }

    override suspend fun updateProgress(
        id: String,
        newProgress: Double,
        source: ProgressSource
    ) {
        recordProgressInternal(
            id = id,
            computeTarget = { current -> Pair(newProgress, current?.userStatus) },
            source = source
        )
    }

    override suspend fun updateMoonReaderChapterIfHigher(id: String, newProgress: Double): Boolean =
        recordProgressInternal(
            id = id,
            computeTarget = { current ->
                if (current != null &&
                    (current.unitName.trim().equals("chapter", ignoreCase = true) ||
                        current.unitName.trim().equals("chapters", ignoreCase = true)) &&
                    newProgress > current.currentUnit
                ) Pair(newProgress, current.userStatus) else null
            },
            source = ProgressSource.DETAIL_SHEET
        )

    override suspend fun updateMangaBakaChapterIfHigher(
        id: String, newProgress: Double, completed: Boolean, seriesStatus: String?
    ): Boolean = recordProgressInternal(
        id = id,
        computeTarget = { current ->
            if (current != null &&
                (current.unitName.trim().equals("chapter", true) ||
                    current.unitName.trim().equals("chapters", true)) &&
                newProgress > current.currentUnit
            ) {
                val shouldCompleteSeries = completed &&
                    seriesStatus?.trim()?.lowercase() in setOf("completed", "cancelled")
                Pair(newProgress, if (shouldCompleteSeries) "completed" else current.userStatus)
            } else null
        },
        source = ProgressSource.DETAIL_SHEET,
        uploadFirst = true
    )

    override suspend fun incrementProgress(
        id: String,
        delta: Double,
        source: ProgressSource
    ) {
        recordProgressInternal(
            id = id,
            computeTarget = { current ->
                val prev = current?.currentUnit ?: 0.0
                val next = BigDecimal.valueOf(prev).add(BigDecimal.valueOf(delta))
                    .max(BigDecimal.ZERO).toDouble()
                val shouldAutoResume = delta > 0 &&
                        (current?.userStatus == "paused" || current?.userStatus == "planning")
                val statusToWrite = if (shouldAutoResume) "in_progress" else current?.userStatus
                Pair(next, statusToWrite)
            },
            source = source
        )
    }

    override suspend fun deleteMediaEntry(id: String): Unit = progressMutex.withLock {
        withContext(Dispatchers.IO) {
            mangaBakaPreferences.unlinkEntry(id)
        }
        database.withTransaction {
            mediaEntryDao.deleteEntryById(id)
            progressHistoryDao.deleteHistoryForEntry(id)
            database.pendingMutationDao().deleteForEntry(id)
            database.pendingMutationDao().upsert(PendingMutation(id, "delete"))
        }
        try {
            retryPendingMutations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to delete entry from Supabase", e)
        }
    }

    override fun getRecentActivityFeed(): Flow<List<ProgressHistoryItem>> {
        return progressHistoryDao.getRecentActivityFeed().map { rows ->
            rows.mapNotNull { row ->
                val delta = (row.newUnit - row.previousUnit).coerceAtLeast(0.0)
                if (delta <= 0.0001) return@mapNotNull null
                ProgressHistoryItem(
                    entryId = row.entryId,
                    activityDate = row.activityDate,
                    previousUnit = row.previousUnit,
                    newUnit = row.newUnit,
                    source = row.source,
                    entryTitle = row.entryTitle ?: "Unknown",
                    mediaType = MediaType.fromValue(row.mediaType ?: "novel"),
                    unitName = row.unitName ?: "Chapter",
                    coverUrl = row.coverUrl
                )
            }
        }
    }

    override fun getProgressStats(): Flow<OverallProgressStats> {
        return getRecentActivityFeed().map { history ->
            // Filter strictly to active reading/watching progress
            val activeHistory = history.filter { ProgressSource.fromValue(it.source).isActiveConsumption }

            var totalUnits = 0.0
            val activeDates = mutableSetOf<String>()
            val typeDeltaMap = mutableMapOf<MediaType, Double>()
            val typeEntryCountMap = mutableMapOf<MediaType, MutableSet<String>>()

            for (item in activeHistory) {
                val delta = item.delta
                totalUnits += delta
                activeDates.add(item.activityDate)
                typeDeltaMap[item.mediaType] = (typeDeltaMap[item.mediaType] ?: 0.0) + delta
                typeEntryCountMap.getOrPut(item.mediaType) { mutableSetOf() }.add(item.entryId)
            }

            // Streak calculation: active days without interruption starting today or yesterday
            val sortedDates = activeDates.sortedDescending()
            var streak = 0
            if (sortedDates.isNotEmpty()) {
                val today = LocalDate.now()
                val yesterday = today.minusDays(1)
                val firstDate = runCatching { LocalDate.parse(sortedDates[0]) }.getOrNull()

                if (firstDate != null && (firstDate == today || firstDate == yesterday)) {
                    var expectedDate = firstDate
                    for (dateStr in sortedDates) {
                        val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: break
                        if (d == expectedDate) {
                            streak++
                            expectedDate = expectedDate.minusDays(1)
                        } else if (d.isBefore(expectedDate)) {
                            break
                        }
                    }
                }
            }

            val breakdown = MediaType.entries.map { type ->
                MediaTypeStats(
                    type = type,
                    totalUnits = typeDeltaMap[type] ?: 0.0,
                    entriesCount = typeEntryCountMap[type]?.size ?: 0
                )
            }

            OverallProgressStats(
                totalUnitsTracked = totalUnits,
                activeDaysCount = activeDates.size,
                currentStreakDays = streak,
                mediaTypeBreakdown = breakdown
            )
        }
    }

}
