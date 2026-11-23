package com.omnitrack.app.data.repository

import android.util.Log
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.local.MediaEntryDao
import com.omnitrack.app.data.local.MediaEntryEntity
import com.omnitrack.app.data.local.ProgressHistoryDao
import com.omnitrack.app.data.local.ProgressHistoryEntity
import com.omnitrack.app.domain.model.*
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class SeriesRepositoryImpl(
    private val mediaEntryDao: MediaEntryDao,
    private val progressHistoryDao: ProgressHistoryDao,
    private val supabaseService: SupabaseService = SupabaseService
) : SeriesRepository {

    // Serializes all progress updates & history writes so rapid taps never read stale state
    private val progressMutex = Mutex()

    override fun getMediaEntriesStream(): Flow<List<MediaEntry>> {
        return mediaEntryDao.getAllEntries().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun fetchFromSupabase() {
        try {
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

            progressHistoryDao.replaceAll(
                history.map { dto ->
                    ProgressHistoryEntity(
                        entryId = dto.entryId,
                        activityDate = dto.activityDate,
                        previousUnit = dto.previousUnit,
                        newUnit = dto.newUnit,
                        source = dto.source,
                        updatedAt = dto.updatedAt.orEmpty()
                    )
                }
            )
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to sync from Supabase", e)
        }
    }

    /**
     * Unified progress persistence pipeline:
     * 1. Captures previousUnit before updating currentUnit.
     * 2. Updates local Room (MediaEntryDao).
     * 3. Upserts daily activity record into ProgressHistoryDao & Supabase progress_history,
     *    preserving the original previous_unit of the day if multiple updates happen on the same date.
     * 4. Updates remote Supabase media_entries.
     */
    private suspend fun recordProgressInternal(
        id: String,
        computeTarget: (current: MediaEntryEntity?) -> Pair<Double, String?>,
        source: ProgressSource
    ) {
        val today = LocalDate.now().toString()

        val (newUnit, statusToWrite, previousUnitToLog) = progressMutex.withLock {
            val current = mediaEntryDao.getEntryById(id)
            val (nextUnit, targetStatus) = computeTarget(current)

            // Update MediaEntry in Room
            if (targetStatus != null && targetStatus != current?.userStatus) {
                mediaEntryDao.updateProgressAndStatus(id, nextUnit, targetStatus)
            } else {
                mediaEntryDao.updateProgress(id, nextUnit)
            }

            var prevUnitForHistory = current?.currentUnit ?: nextUnit
            val hasNetProgress = (nextUnit - prevUnitForHistory) > 0.0001

            // Only log daily consumption progress_history for active reading/watching sessions with positive progress
            if (source.isActiveConsumption && hasNetProgress) {
                // Preserve the original previous_unit of the day if a record for today already exists
                val existingTodayHistory = progressHistoryDao.getEntryHistoryForDate(id, today)
                prevUnitForHistory = existingTodayHistory?.previousUnit
                    ?: (current?.currentUnit ?: nextUnit)

                // Update local Room ProgressHistory
                progressHistoryDao.upsert(
                    ProgressHistoryEntity(
                        entryId = id,
                        activityDate = today,
                        previousUnit = prevUnitForHistory,
                        newUnit = nextUnit,
                        source = source.value
                    )
                )
            }

            Triple(nextUnit, targetStatus, prevUnitForHistory)
        }

        // Sync to Supabase
        try {
            supabaseService.updateEntryProgress(
                entryId = id,
                currentUnit = newUnit,
                updatedStatus = statusToWrite
            )
            val hasNetProgress = (newUnit - previousUnitToLog) > 0.0001
            if (source.isActiveConsumption && hasNetProgress) {
                supabaseService.logProgress(
                    entryId = id,
                    activityDate = today,
                    prevUnit = previousUnitToLog,
                    newUnit = newUnit,
                    source = source.value
                )
            }
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to sync progress to Supabase", e)
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

    override suspend fun incrementProgress(
        id: String,
        delta: Double,
        source: ProgressSource
    ) {
        recordProgressInternal(
            id = id,
            computeTarget = { current ->
                val prev = current?.currentUnit ?: 0.0
                val next = (prev + delta).coerceAtLeast(0.0)
                val shouldAutoResume = delta > 0 &&
                        (current?.userStatus == "paused" || current?.userStatus == "planning")
                val statusToWrite = if (shouldAutoResume) "in_progress" else current?.userStatus
                Pair(next, statusToWrite)
            },
            source = source
        )
    }

    override suspend fun deleteMediaEntry(id: String) {
        mediaEntryDao.deleteEntryById(id)
        progressHistoryDao.deleteHistoryForEntry(id)
        try {
            supabaseService.deleteEntry(id)
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
