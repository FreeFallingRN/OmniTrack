package com.omnitrack.app.data.repository

import android.util.Log
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.local.MediaEntryDao
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class SeriesRepositoryImpl(
    private val mediaEntryDao: MediaEntryDao,
    private val supabaseService: SupabaseService = SupabaseService
) : SeriesRepository {

    // Serializes all increment operations so rapid taps never read stale Room state
    private val incrementMutex = Mutex()

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
        try {
            supabaseService.updateEntryProgress(entryId = id, currentUnit = newProgress)
            supabaseService.logProgress(
                entryId = id,
                activityDate = LocalDate.now().toString(),
                prevUnit = previousEntry?.currentUnit ?: newProgress,
                newUnit = newProgress,
                source = "android_app"
            )
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to update progress on Supabase", e)
        }
    }

    override suspend fun incrementProgress(id: String, delta: Double) {
        // Hold the mutex for the full read-compute-write cycle so rapid taps are serialized
        val (newUnit, newStatus, prevUnit) = incrementMutex.withLock {
            val current = mediaEntryDao.getEntryById(id)
            val prev = current?.currentUnit ?: 0.0
            val next = (prev + delta).coerceAtLeast(0.0)

            // Auto-resume PAUSED / PLANNING entries on a positive increment
            val shouldAutoResume = delta > 0 &&
                    (current?.userStatus == "paused" || current?.userStatus == "planning")
            val statusToWrite = if (shouldAutoResume) "in_progress" else current?.userStatus

            if (statusToWrite != null && statusToWrite != current?.userStatus) {
                mediaEntryDao.updateProgressAndStatus(id, next, statusToWrite)
            } else {
                mediaEntryDao.updateProgress(id, next)
            }
            Triple(next, statusToWrite, prev)
        }

        try {
            supabaseService.updateEntryProgress(
                entryId = id,
                currentUnit = newUnit,
                updatedStatus = newStatus
            )
            supabaseService.logProgress(
                entryId = id,
                activityDate = LocalDate.now().toString(),
                prevUnit = prevUnit,
                newUnit = newUnit,
                source = "android_app"
            )
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to increment progress on Supabase", e)
        }
    }

    override suspend fun deleteMediaEntry(id: String) {
        mediaEntryDao.deleteEntryById(id)
        try {
            supabaseService.deleteEntry(id)
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Failed to delete entry from Supabase", e)
        }
    }
}

