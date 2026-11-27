package com.omnitrack.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.MangaBakaProgressSync
import com.omnitrack.app.data.AniListProgressSync
import com.omnitrack.app.data.AniListPreferences
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.local.MediaEntryDao
import com.omnitrack.app.data.local.MediaEntryEntity
import com.omnitrack.app.data.local.PendingMutation
import com.omnitrack.app.data.local.ProgressHistoryDao
import com.omnitrack.app.data.local.ProgressHistoryEntity
import com.omnitrack.app.domain.model.*
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.domain.repository.PartialMediaEntryCreateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val appPreferences: OmniTrackPreferences,
    private val aniListPreferences: AniListPreferences,
    private val supabaseService: SupabaseService = SupabaseService
) : SeriesRepository {

    // Keep refreshes and progress uploads ordered with local progress and history writes.
    companion object {
        private val progressMutex = Mutex()
    }

    private suspend fun retryPendingMutations() {
        val pendingDao = database.pendingMutationDao()
        for (mutation in pendingDao.getAll()) {
            if (mutation.kind in setOf("anilist_status", "mangabaka_status", "anilist_unresolved", "mangabaka_unresolved")) continue
            when (mutation.kind) {
                "delete" -> supabaseService.deleteEntry(mutation.entryId)
                "progress" -> {
                    val entry = checkNotNull(mediaEntryDao.getEntryById(mutation.entryId)) {
                        "Pending progress entry ${mutation.entryId} is missing"
                    }
                    supabaseService.updateEntryProgress(entry.id, entry.currentUnit, mutation.userStatus)
                }
                "anilist_cover" -> {
                    val entry = checkNotNull(mediaEntryDao.getEntryById(mutation.entryId)) {
                        "Pending cover entry ${mutation.entryId} is missing"
                    }
                    val cover = checkNotNull(entry.coverUrl?.takeIf { it.isNotBlank() }) {
                        "Pending cover for ${entry.id} is missing"
                    }
                    val remoteCover = supabaseService.backfillEntryCover(entry.id, cover)
                    mediaEntryDao.insertAll(listOf(entry.copy(coverUrl = remoteCover)))
                }
                "anilist_url" -> {
                    val entry = checkNotNull(mediaEntryDao.getEntryById(mutation.entryId)) {
                        "Pending URL entry ${mutation.entryId} is missing"
                    }
                    val url = checkNotNull(entry.sourceUrl?.takeIf { it.isNotBlank() }) {
                        "Pending URL for ${entry.id} is missing"
                    }
                    supabaseService.updateEntrySourceUrl(entry.id, url)
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

    override suspend fun getSeries(): List<Series> = progressMutex.withLock {
        supabaseService.getSeries().map { it.toDomain() }
    }

    override suspend fun getAuthors(): List<Author> = progressMutex.withLock {
        supabaseService.getAuthors().map { it.toDomain() }
    }

    override suspend fun getPlatforms(): List<Platform> = progressMutex.withLock {
        supabaseService.getPlatforms().map { it.toDomain() }
    }

    override suspend fun createSeries(request: CreateSeriesCommand): Series =
        mutateLibrary { supabaseService.createSeries(request.toDto()).toDomain() }

    override suspend fun updateSeries(id: String, title: String, rating: Int?) =
        mutateLibrary { supabaseService.updateSeries(id, title, rating) }

    override suspend fun deleteSeries(id: String) = mutateLibrary { supabaseService.deleteSeries(id) }

    override suspend fun createAuthor(
        name: String,
        nativeName: String?,
        imageUrl: String?,
        bio: String?
    ): Author = mutateLibrary { supabaseService.createAuthor(name, nativeName, imageUrl, bio).toDomain() }

    override suspend fun updateAuthor(
        id: String,
        name: String,
        nativeName: String?,
        imageUrl: String?,
        bio: String?
    ) = mutateLibrary { supabaseService.updateAuthor(id, name, nativeName, imageUrl, bio) }

    override suspend fun deleteAuthor(id: String) = mutateLibrary { supabaseService.deleteAuthor(id) }

    override suspend fun createPlatform(request: CreatePlatformCommand): Platform =
        mutateLibrary { supabaseService.createPlatform(request.toDto()).toDomain() }

    override suspend fun updatePlatform(id: String, request: UpdatePlatformCommand) =
        mutateLibrary { supabaseService.updatePlatform(id, request.toDto()) }

    override suspend fun deletePlatform(id: String) = mutateLibrary { supabaseService.deletePlatform(id) }

    override suspend fun createMediaEntry(
        request: CreateEntryCommand,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ): CreatedMediaEntry {
        var createdId: String? = null
        try {
            return mutateLibrary {
                val created = supabaseService.createEntry(request.toDto())
                createdId = created.id
                supabaseService.replaceEntryAuthors(created.id, creators)
                supabaseService.replaceEntryAlternateTitles(created.id, request.seriesId, alternativeTitles)
                CreatedMediaEntry(created.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (createdId == null && e !is PartialMediaEntryCreateException) throw e
            val reconciled = try {
                fetchFromSupabase()
                true
            } catch (refreshError: CancellationException) {
                throw refreshError
            } catch (refreshError: Exception) {
                Log.e("SeriesRepository", "Failed to reconcile partial entry creation", refreshError)
                e.addSuppressed(refreshError)
                false
            }
            val entryId = createdId ?: (e as? PartialMediaEntryCreateException)?.entryId
            val entry = entryId?.let { "Entry $it" } ?: "The entry"
            val guidance = if (reconciled) "Library refreshed." else "Library refresh failed; refresh it manually."
            throw PartialMediaEntryCreateException(
                entryId,
                "$entry may already exist remotely. $guidance Check the library and edit its creators and aliases before retrying.",
                e
            )
        }
    }

    override suspend fun updateMediaEntry(
        id: String,
        request: UpdateEntryCommand,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ): Boolean = AniListProgressSync.withLinkLock { MangaBakaProgressSync.withLinkLock {
        var progressChanged = false
        var statusChanged = false
        mutateLibrary {
            val current = mediaEntryDao.getEntryById(id)
            if (current != null && request.currentUnit < current.currentUnit) {
                checkResetAllowed(current)
                if (request.type == MediaType.COMIC.value && current.type != request.type) {
                    check(!mangaBakaPreferences.isMangaBakaLinked(id)) {
                        "Unlink the progress source before resetting ${current.title}"
                    }
                }
            }
            check(current == null || current.seriesId == null || current.seriesAlternativeTitles != null) {
                "Refresh the library before editing this entry so its aliases can be separated safely."
            }
            progressChanged = current != null && current.currentUnit != request.currentUnit
            statusChanged = current != null && current.userStatus != request.userStatus
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
        if (progressChanged) recordProgressInternal(
            id, { current -> Pair(request.currentUnit, current?.userStatus) },
            ProgressSource.EDITOR, blockReset = true
        )
        if (statusChanged) updateEntryStatus(id, request.userStatus)
        statusChanged
    } }

    override suspend fun updateEntryStatus(id: String, status: String): Unit = progressMutex.withLock {
        require(UserStatus.entries.any { it.value == status }) { "Invalid user status" }
        val entry = checkNotNull(mediaEntryDao.getEntryById(id)) { "Entry no longer exists" }
        val aniLinks = try {
            val credentials = aniListPreferences.read()
            if (credentials.userId > 0) appPreferences.aniListLinks(credentials.userId)
                .filterValues { it.entryId == id }.keys.map { "${credentials.userId}:$it" }
            else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Could not identify AniList links; status sync pending", e)
            null
        }
        val mangaLinks = try {
            val credentials = mangaBakaPreferences.read()
            if (credentials.userId.isNotBlank()) mangaBakaPreferences.links(credentials.userId)
                .filterValues { it.entryId == id }.keys.map { "${credentials.userId}:$it" }
            else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SeriesRepository", "Could not identify MangaBaka links; status sync pending", e)
            null
        }
        database.withTransaction {
            mediaEntryDao.insertAll(listOf(entry.copy(userStatus = status)))
            val pending = database.pendingMutationDao()
            pending.upsert(PendingMutation(id, "progress", userStatus = status))
            if (aniLinks == null) {
                pending.getAll().filter { it.entryId == id && it.kind == "anilist_status" }
                    .forEach { pending.upsert(it.copy(userStatus = status)) }
                pending.upsert(PendingMutation(id, "anilist_unresolved", userStatus = status))
            } else {
                pending.delete(id, "anilist_unresolved", "")
                aniLinks.forEach { key -> pending.upsert(PendingMutation(id, "anilist_status", key, status)) }
            }
            if (mangaLinks == null) {
                pending.getAll().filter { it.entryId == id && it.kind == "mangabaka_status" }
                    .forEach { pending.upsert(it.copy(userStatus = status)) }
                pending.upsert(PendingMutation(id, "mangabaka_unresolved", userStatus = status))
            } else {
                pending.delete(id, "mangabaka_unresolved", "")
                mangaLinks.forEach { key -> pending.upsert(PendingMutation(id, "mangabaka_status", key, status)) }
            }
        }
    }

    override suspend fun pendingTrackerStatus(id: String, service: String, linkKey: String): String? =
        progressMutex.withLock { database.pendingMutationDao().get(id, service, linkKey)?.userStatus }

    override suspend fun clearPendingTrackerStatus(id: String, service: String, linkKey: String, status: String) {
        progressMutex.withLock {
            val pending = database.pendingMutationDao()
            if (pending.get(id, service, linkKey)?.userStatus == status) {
                pending.delete(id, service, linkKey)
            }
        }
    }

    override suspend fun resolvePendingTrackerStatuses(service: String, links: Map<String, List<String>>) {
        progressMutex.withLock {
            val pending = database.pendingMutationDao()
            database.withTransaction {
                for (mutation in pending.getAll().filter { it.kind == "${service}_unresolved" }) {
                    val keys = links[mutation.entryId].orEmpty()
                    if (keys.isEmpty()) continue
                    for (key in keys) {
                        if (pending.get(mutation.entryId, "${service}_status", key) == null) {
                            pending.upsert(PendingMutation(mutation.entryId, "${service}_status", key, mutation.userStatus))
                        }
                    }
                    pending.delete(mutation.entryId, mutation.kind, mutation.activityDate)
                }
            }
        }
    }

    override suspend fun updateMangaBakaUrl(id: String, sourceUrl: String): Unit = progressMutex.withLock {
        val entry = mediaEntryDao.getEntryById(id) ?: error("Entry no longer exists")
        supabaseService.updateMangaBakaUrl(id, sourceUrl)
        mediaEntryDao.insertAll(listOf(entry.copy(sourceUrl = sourceUrl)))
    }

    override suspend fun updateAniListUrl(id: String, sourceUrl: String): Boolean = progressMutex.withLock {
        val entry = checkNotNull(mediaEntryDao.getEntryById(id)) { "Entry no longer exists" }
        val changed = entry.sourceUrl != sourceUrl
        if (changed) {
            database.withTransaction {
                mediaEntryDao.insertAll(listOf(entry.copy(sourceUrl = sourceUrl)))
                database.pendingMutationDao().upsert(PendingMutation(id, "anilist_url"))
            }
        }
        retryPendingMutations()
        changed
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

        val localEntries = mediaEntryDao.getAllEntries().first().associateBy { it.id }
        val entrySnapshot = entries.map { entry ->
            entry.toEntity(
                seriesTitle = seriesTitles[entry.seriesId],
                creators = creatorsByEntry[entry.id].orEmpty(),
                altTitles = altTitlesByEntry[entry.id].orEmpty().map { it.title },
                seriesAltTitles = entry.seriesId?.let { altTitlesBySeries[it] }.orEmpty().map { it.title }
            ).copy(totalEpisodes = localEntries[entry.id]?.totalEpisodes?.takeIf {
                entry.type == MediaType.ANIME.value &&
                    (entry.unitName.trim().equals("episode", true) ||
                        entry.unitName.trim().equals("episodes", true))
            })
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

    override suspend fun retryPendingLibraryMutations(): Unit = progressMutex.withLock {
        retryPendingMutations()
    }

    // MangaBaka uploads first so a failed Supabase write can be retried without losing the delta.
    private fun checkResetAllowed(current: MediaEntryEntity) {
        val mangaBakaLocked = current.type == MediaType.COMIC.value &&
            mangaBakaPreferences.isMangaBakaLinked(current.id)
        check(!mangaBakaLocked && !mangaBakaPreferences.isMoonReaderLinked(current.id)) {
            "Unlink the progress source before resetting ${current.title}"
        }
    }

    private suspend fun recordProgressInternal(
        id: String,
        computeTarget: suspend (current: MediaEntryEntity?) -> Pair<Double, String?>?,
        source: ProgressSource,
        uploadFirst: Boolean = false,
        blockReset: Boolean = false,
        recordSyncHistory: Boolean = false
    ): Boolean {
        return progressMutex.withLock {
            val today = LocalDate.now().toString()
            val current = mediaEntryDao.getEntryById(id)
            val (nextUnit, targetStatus) = computeTarget(current) ?: return@withLock false
            checkNotNull(current) { "Entry $id no longer exists" }
            if (blockReset && nextUnit < current.currentUnit) checkResetAllowed(current)

            var prevUnitForHistory = current.currentUnit
            val hasNetProgress = if (recordSyncHistory) nextUnit > prevUnitForHistory
                else (nextUnit - prevUnitForHistory) > 0.0001
            val recordHistory = hasNetProgress && (source.isActiveConsumption || recordSyncHistory)
            var historySource = source

            if (recordHistory) {
                val existingTodayHistory = progressHistoryDao.getEntryHistoryForDate(id, today)
                if (existingTodayHistory != null &&
                    ProgressSource.fromValue(existingTodayHistory.source).isActiveConsumption) {
                    prevUnitForHistory = existingTodayHistory.previousUnit +
                        (current.currentUnit - existingTodayHistory.newUnit).coerceAtLeast(0.0)
                    if (!source.isActiveConsumption) {
                        historySource = ProgressSource.fromValue(existingTodayHistory.source)
                    }
                }
            }

            suspend fun uploadHistory() {
                if (recordHistory) {
                    check(
                        supabaseService.logProgress(
                            entryId = id,
                            activityDate = today,
                            prevUnit = prevUnitForHistory,
                            newUnit = nextUnit,
                            source = historySource.value
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
                if (recordHistory) {
                    progressHistoryDao.upsert(
                        ProgressHistoryEntity(
                            entryId = id, activityDate = today, previousUnit = prevUnitForHistory,
                            newUnit = nextUnit, source = historySource.value
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
        MangaBakaProgressSync.withLinkLock {
            recordProgressInternal(
                id = id,
                computeTarget = { current -> Pair(newProgress, current?.userStatus) },
                source = source,
                blockReset = true
            )
        }
    }

    override suspend fun updateMoonReaderChapterIfHigher(id: String, newProgress: Double): Boolean =
        recordProgressInternal(
            id = id,
            computeTarget = { current ->
                checkNotNull(current) { "Linked entry $id no longer exists" }
                check((current.type == MediaType.NOVEL.value || current.type == MediaType.COMIC.value) &&
                    (current.unitName.trim().equals("chapter", true) ||
                        current.unitName.trim().equals("chapters", true)) &&
                    !(current.type == MediaType.COMIC.value && mangaBakaPreferences.isMangaBakaLinked(id))) {
                    "${current.title} can no longer sync from Moon+ Reader"
                }
                if (newProgress > current.currentUnit) Pair(newProgress, current.userStatus) else null
            },
            source = ProgressSource.TRACKING_SYNC,
            recordSyncHistory = true
        )

    override suspend fun updateMoonReaderProgress(id: String, newProgress: Double, source: ProgressSource) {
        recordProgressInternal(
            id,
            { current ->
                check(current != null && (current.type == MediaType.NOVEL.value ||
                    current.type == MediaType.COMIC.value)) { "Moon+ Reader requires a novel or comic" }
                check(!(current.type == MediaType.COMIC.value && mangaBakaPreferences.isMangaBakaLinked(id))) {
                    "Unlink MangaBaka before updating comic progress with Moon+ Reader"
                }
                Pair(newProgress, current.userStatus)
            },
            source,
            blockReset = true
        )
    }

    override suspend fun updateAniListEpisodeIfHigher(
        id: String,
        newProgress: Double,
        initial: Boolean,
        status: String?
    ): Boolean =
        recordProgressInternal(
            id = id,
            computeTarget = { current ->
                checkNotNull(current) { "Linked entry $id no longer exists" }
                check(newProgress.isFinite() && newProgress in 0.0..99999.99) {
                    "${current.title}: AniList episode progress cannot be represented in OmniTrack"
                }
                check(current.type == MediaType.ANIME.value &&
                    (current.unitName.trim().equals("episode", true) ||
                        current.unitName.trim().equals("episodes", true))) {
                    "${current.title} can no longer sync from AniList"
                }
                val credentials = aniListPreferences.read()
                val links = if (credentials.userId > 0) appPreferences.aniListLinks(credentials.userId) else emptyMap()
                val statusPending = links.any { (mediaId, link) ->
                    link.entryId == id && database.pendingMutationDao()
                        .get(id, "anilist_status", "${credentials.userId}:$mediaId") != null
                }
                val targetStatus = when (if (statusPending) null else status?.uppercase()) {
                    "CURRENT", "REPEATING" -> "in_progress"
                    "COMPLETED" -> "completed"
                    "PAUSED" -> "paused"
                    "DROPPED" -> "dropped"
                    "PLANNING" -> "planning"
                    else -> current.userStatus
                }
                val targetProgress = maxOf(current.currentUnit, newProgress)
                if (targetProgress > current.currentUnit || targetStatus != current.userStatus) {
                    Pair(targetProgress, targetStatus)
                } else null
            },
            source = if (initial) ProgressSource.IMPORT else ProgressSource.TRACKING_SYNC
        )

    override suspend fun updateAniListMetadata(
        id: String,
        coverUrl: String?,
        totalEpisodes: Int?
    ): Boolean = progressMutex.withLock {
        val current = checkNotNull(mediaEntryDao.getEntryById(id)) { "Linked entry $id no longer exists" }
        check(current.type == MediaType.ANIME.value &&
            (current.unitName.trim().equals("episode", true) ||
                current.unitName.trim().equals("episodes", true))) {
            "${current.title} can no longer sync from AniList"
        }
        val cover = coverUrl?.trim()?.takeIf { it.isNotBlank() && current.coverUrl.isNullOrBlank() }
        val total = totalEpisodes?.takeIf { it > 0 } ?: current.totalEpisodes
        val changed = cover != null || total != current.totalEpisodes
        if (changed) {
            database.withTransaction {
                mediaEntryDao.insertAll(listOf(current.copy(
                    coverUrl = cover ?: current.coverUrl,
                    totalEpisodes = total
                )))
                if (cover != null) database.pendingMutationDao().upsert(PendingMutation(id, "anilist_cover"))
            }
        }
        retryPendingMutations()
        changed
    }

    override suspend fun updateMangaBakaChapterIfHigher(
        id: String, newProgress: Double, completed: Boolean, seriesStatus: String?
    ): Boolean = recordProgressInternal(
        id = id,
        computeTarget = { current ->
            checkNotNull(current) { "Linked entry $id no longer exists" }
            check(current.type == MediaType.COMIC.value &&
                (current.unitName.trim().equals("chapter", true) ||
                    current.unitName.trim().equals("chapters", true))) {
                "${current.title} can no longer sync from MangaBaka"
            }
            if (newProgress > current.currentUnit) {
                val credentials = mangaBakaPreferences.read()
                val links = if (credentials.userId.isNotBlank()) mangaBakaPreferences.links(credentials.userId) else emptyMap()
                val statusPending = links.any { (seriesId, link) ->
                    link.entryId == id && database.pendingMutationDao()
                        .get(id, "mangabaka_status", "${credentials.userId}:$seriesId") != null
                }
                val shouldCompleteSeries = !statusPending && completed &&
                    seriesStatus?.trim()?.lowercase() in setOf("completed", "cancelled")
                Pair(newProgress, if (shouldCompleteSeries) "completed" else current.userStatus)
            } else null
        },
        source = ProgressSource.TRACKING_SYNC,
        uploadFirst = true,
        recordSyncHistory = true
    )

    override suspend fun incrementProgress(
        id: String,
        delta: Double,
        source: ProgressSource
    ) {
        MangaBakaProgressSync.withLinkLock {
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
                source = source,
                blockReset = true
            )
        }
    }

    override suspend fun deleteMediaEntry(id: String): Unit = AniListProgressSync.withLinkLock {
        MangaBakaProgressSync.withLinkLock {
            progressMutex.withLock {
                withContext(Dispatchers.IO) {
                    mangaBakaPreferences.unlinkEntry(id)
                    mangaBakaPreferences.unlinkMoonReader(id)
                    appPreferences.unlinkAniListEntry(id)
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
        }
    }

    override fun getRecentActivityFeed(): Flow<List<ProgressHistoryItem>> {
        return progressHistoryDao.getRecentActivityFeed().map { rows ->
            rows.mapNotNull { row ->
                val delta = (row.newUnit - row.previousUnit).coerceAtLeast(0.0)
                if (delta <= 0.0) return@mapNotNull null
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
