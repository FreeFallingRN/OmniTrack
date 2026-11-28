package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.data.AniListCredentials
import com.omnitrack.app.data.AniListLibraryEntry
import com.omnitrack.app.data.AniListLink
import com.omnitrack.app.data.AniListPreferences
import com.omnitrack.app.data.AniListProgressSync
import com.omnitrack.app.data.AniListService
import com.omnitrack.app.data.AppLogger
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.CreateEntryCommand
import com.omnitrack.app.domain.model.CreatePlatformCommand
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.feature.anilist.matching.findAniListMatch
import com.omnitrack.app.worker.TrackerStatusSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AniListState(
    val initialized: Boolean = false,
    val connected: Boolean = false,
    val username: String = "",
    val userId: Int = 0,
    val avatarUrl: String? = null,
    val expiresAt: Long = 0,
    val autoSync: Boolean = true,
    val batchImporting: Boolean = false,
    val batchCancelRequested: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val hasError: Boolean = false,
    val titles: List<AniListLibraryEntry> = emptyList(),
    val links: Map<Int, AniListLink> = emptyMap()
)

class AniListOwner(
    context: Context,
    private val repository: SeriesRepository,
    private val isRefreshing: StateFlow<Boolean>
) : ViewModel() {
    private val context = context.applicationContext
    private val credentialsStore = AniListPreferences(this.context)
    private val preferences = OmniTrackPreferences(this.context)
    private val service = AniListService(credentialsStore)
    private val progressSync = AniListProgressSync(credentialsStore, preferences, service, repository)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AniListState())
    val state = _state.asStateFlow()
    private var credentials = AniListCredentials()
    private var lastSyncAttempt: Long? = null
    private val listener = preferences.addListener {
        if (credentials.userId > 0) _state.update { state ->
            state.copy(links = preferences.aniListLinks(credentials.userId))
        }
    }

    init {
        viewModelScope.launch {
            try {
                operation {
                    credentials = withContext(Dispatchers.IO) { credentialsStore.read() }
                    _state.update { it.copy(
                        connected = credentials.accessToken.isNotBlank() && credentials.userId > 0,
                        username = credentials.username,
                        userId = credentials.userId, avatarUrl = credentials.avatarUrl, expiresAt = credentials.expiresAt,
                        autoSync = preferences.getInt(OmniTrackPreferenceKeys.AniListAutoSync, 1) != 0,
                        links = if (credentials.userId > 0) preferences.aniListLinks(credentials.userId) else emptyMap()
                    ) }
                }
            } finally {
                _state.update { it.copy(initialized = true) }
            }
        }
    }

    suspend fun authorizationUrl(): String = mutex.withLock {
        AniListProgressSync.withLinkLock {
            withContext(Dispatchers.IO) {
                service.authorizationUrl().also { credentials = credentialsStore.read() }
            }
        }
    }

    fun handleCallback(uri: Uri) {
        viewModelScope.launch {
            operation {
                credentials = AniListProgressSync.withLinkLock {
                    try {
                        service.completeAuthorization(uri)
                    } finally {
                        credentials = withContext(Dispatchers.IO) { credentialsStore.read() }
                    }
                }
                _state.update { it.copy(
                    initialized = true, connected = true, username = credentials.username,
                    userId = credentials.userId, avatarUrl = credentials.avatarUrl, expiresAt = credentials.expiresAt,
                    links = preferences.aniListLinks(credentials.userId), titles = emptyList(),
                    message = "AniList connected. Fetch and match your library."
                ) }
                TrackerStatusSyncWorker.schedule(context)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            operation {
                AniListProgressSync.withLinkLock { withContext(Dispatchers.IO) { service.disconnect() } }
                credentials = AniListCredentials()
                _state.update { it.copy(connected = false, username = "", userId = 0, avatarUrl = null, expiresAt = 0, links = emptyMap(),
                    titles = emptyList(), message = "AniList disconnected.") }
            }
        }
    }

    fun fetch() {
        viewModelScope.launch {
            operation {
                val titles = service.library(credentials)
                _state.update { it.copy(titles = titles, message = "Fetched ${titles.size} AniList titles.") }
            }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            operation {
                check(preferences.edit().putInt(OmniTrackPreferenceKeys.AniListAutoSync, if (enabled) 1 else 0).commit()) {
                    "Could not save AniList auto-sync setting"
                }
                _state.update { it.copy(autoSync = enabled) }
            }
        }
    }

    fun linkAllMatched() {
        viewModelScope.launch {
            isRefreshing.first { !it }
            operation {
                AniListProgressSync.withLinkLock {
                    check(credentialsStore.read() == credentials && credentials.userId > 0) {
                        "AniList account changed; reconnect and try again"
                    }
                    val links = preferences.aniListLinks(credentials.userId).toMutableMap()
                    val entries = repository.getMediaEntriesStream().first()
                        .filter { entry -> links.values.none { it.entryId == entry.id } }
                    val matches = _state.value.titles.groupBy { it.mediaId }.values
                        .filter { it.size == 1 }
                        .mapNotNull { group ->
                            val title = group.single()
                            if (title.mediaId in links) null
                            else findAniListMatch(title, entries)?.let { title to it.id }
                        }
                    val unique = matches.groupBy { it.second }.values.filter { it.size == 1 }.map { it.single() }
                    val failures = mutableListOf<String>()
                    for ((title, entryId) in unique) {
                        links[title.mediaId] = AniListLink(entryId, mediaListId = title.id)
                        preferences.saveAniListLinks(credentials.userId, links)
                        try {
                            repository.updateAniListUrl(entryId, title.sourceUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error("Could not sync AniList URL", e, secrets = listOf(title.displayTitle))
                            failures.add("${title.displayTitle}: ${e.message ?: "URL sync failed"}")
                        }
                    }
                    _state.update { it.copy(links = links.toMap(),
                        message = "Linked ${unique.size} matches; ${matches.size - unique.size} ambiguous matches skipped. Sync now to import progress." +
                            if (failures.isEmpty()) "" else " URL updates pending: ${failures.joinToString("; ")}",
                        hasError = failures.isNotEmpty()) }
                }
            }
        }
    }

    fun cancelBatchImport() {
        _state.update { if (it.batchImporting) it.copy(batchCancelRequested = true) else it }
    }

    fun batchImport(titles: List<AniListLibraryEntry>) {
        viewModelScope.launch {
            isRefreshing.first { !it }
            operation {
                _state.update { it.copy(batchImporting = true, batchCancelRequested = false) }
                var imported = 0
                var processed = 0
                val issues = mutableListOf<String>()
                try {
                    for (title in titles) {
                        if (_state.value.batchCancelRequested) break
                        try {
                            AniListProgressSync.withLinkLock {
                                withContext(Dispatchers.IO) {
                                    check(credentialsStore.read() == credentials && credentials.userId > 0) {
                                        "AniList account changed; reconnect and try again"
                                    }
                                    val links = preferences.aniListLinks(credentials.userId)
                                    check(title.mediaId !in links) { "Already linked" }
                                    check(_state.value.titles.count { it.mediaId == title.mediaId } == 1) {
                                        "Title is missing or duplicated; fetch the library again"
                                    }
                                    val progress = checkNotNull(title.progress) { "Episode progress is unavailable" }
                                    check(progress in 0..99999) { "Episode progress is out of range" }
                                    val status = when (title.status) {
                                        "CURRENT", "REPEATING" -> "in_progress"
                                        "COMPLETED" -> "completed"
                                        "PAUSED" -> "paused"
                                        "DROPPED" -> "dropped"
                                        "PLANNING" -> "planning"
                                        else -> error("Status is unavailable")
                                    }
                                    val publishingStatus = when (title.media.status) {
                                        "FINISHED" -> "completed"
                                        "NOT_YET_RELEASED" -> "announced"
                                        "CANCELLED" -> "discontinued"
                                        "HIATUS" -> "hiatus"
                                        "RELEASING" -> "releasing"
                                        else -> error("Publication status is unavailable")
                                    }
                                    val studios = title.media.studios?.nodes.orEmpty()
                                        .map { it.name.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                                    check(studios.isNotEmpty()) { "Studio is unavailable; use Add as new" }
                                    val entries = repository.getMediaEntriesStream().first()
                                    val url = title.sourceUrl
                                    check(entries.none {
                                        it.sourceUrl?.trimEnd('/')?.equals(url, true) == true ||
                                            findAniListMatch(title, listOf(it.copy(unitName = "Episode"))) != null
                                    }) { "Possible existing entry; link or review manually" }
                                    val authors = repository.getAuthors().toMutableList()
                                    val creators = studios.map { name ->
                                        val author = authors.firstOrNull { it.name.trim().equals(name, true) }
                                            ?: repository.createAuthor(name, null, null, null).also { authors.add(it) }
                                        author.id to "studio"
                                    }
                                    val platform = repository.getPlatforms().firstOrNull {
                                        it.name.trim().equals("AniList", true)
                                    } ?: repository.createPlatform(CreatePlatformCommand(name = "AniList"))
                                    check(credentialsStore.read() == credentials &&
                                        !preferences.aniListLinks(credentials.userId).containsKey(title.mediaId)) {
                                        "AniList account or link changed"
                                    }
                                    val created = try {
                                        repository.createMediaEntry(
                                            CreateEntryCommand(
                                                title = title.displayTitle, type = MediaType.ANIME.value,
                                                sequenceNumber = null, userStatus = status, publishingStatus = publishingStatus,
                                                currentUnit = progress.toDouble(), unitName = "Episode", sourceUrl = url,
                                                platformId = platform.id, coverUrl = title.media.coverImage?.large
                                            ), creators, emptyList()
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        throw IllegalStateException("Entry may have been created; check the library before retrying: ${e.message}", e)
                                    }
                                    try {
                                        val current = preferences.aniListLinks(credentials.userId)
                                        check(credentialsStore.read() == credentials && title.mediaId !in current &&
                                            current.values.none { it.entryId == created.id }) { "AniList account or link changed" }
                                        preferences.saveAniListLinks(credentials.userId, current + (title.mediaId to AniListLink(
                                            created.id, initialSync = false, mediaListId = title.id,
                                            lastProgress = progress, lastUpdatedAt = title.updatedAt
                                        )))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        throw IllegalStateException("Entry ${created.id} was created but linking failed; link it manually: ${e.message}", e)
                                    }
                                    if (title.media.episodes == 1 || title.media.format in setOf("MOVIE", "OVA", "ONA", "SPECIAL")) {
                                        try {
                                            repository.updateAniListMetadata(
                                                created.id, title.media.coverImage?.large, title.media.episodes
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            AppLogger.error("Could not import metadata", e, secrets = listOf(title.displayTitle))
                                            issues.add("${title.displayTitle}: linked, but metadata sync failed: ${e.message ?: "unknown error"}")
                                        }
                                    }
                                }
                            }
                            imported++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error("Could not import AniList title", e, secrets = listOf(title.displayTitle))
                            issues.add("${title.displayTitle}: ${e.message ?: "Import failed"}")
                        }
                        processed++
                        _state.update { it.copy(message = "Batch import: $imported imported, $processed/${titles.size} processed.") }
                    }
                } finally {
                    _state.update {
                        it.copy(batchImporting = false, batchCancelRequested = false,
                            message = "Batch import ${if (processed < titles.size) "stopped" else "finished"}: " +
                                "$imported imported, ${issues.size} need review." +
                                if (issues.isEmpty()) "" else " ${issues.joinToString("; ")}",
                            hasError = issues.isNotEmpty())
                    }
                }
            }
        }
    }

    fun match() {
        viewModelScope.launch {
            isRefreshing.first { !it }
            operation {
                val titles = service.library(credentials)
                AniListProgressSync.withLinkLock {
                    check(credentialsStore.read() == credentials) { "AniList account changed; try again" }
                    val entries = repository.getMediaEntriesStream().first()
                    val links = preferences.aniListLinks(credentials.userId).toMutableMap()
                    val linkedIds = links.values.map { it.entryId }.toSet()
                    val available = entries.filter { it.id !in linkedIds }
                    val matches = titles.groupBy { it.mediaId }.values.filter { it.size == 1 }
                        .mapNotNull { group ->
                            val title = group.single()
                            if (title.mediaId in links) null
                            else findAniListMatch(title, available)?.let { title to it.id }
                        }
                    val unique = matches.groupBy { it.second }.values.filter { it.size == 1 }.map { it.single() }
                    val failures = mutableListOf<String>()
                    for ((title, entryId) in unique) {
                        val link = AniListLink(entryId, mediaListId = title.id)
                        links[title.mediaId] = link
                        preferences.saveAniListLinks(credentials.userId, links)
                        try {
                            repository.updateAniListUrl(entryId, title.sourceUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error("Could not sync AniList URL", e, secrets = listOf(title.displayTitle))
                            failures.add("${title.displayTitle}: ${e.message ?: "URL sync failed"}")
                        }
                        try {
                            repository.updateAniListMetadata(
                                entryId, title.media.coverImage?.large, title.media.episodes
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error("Could not import AniList metadata", e, secrets = listOf(title.displayTitle))
                            failures.add("${title.displayTitle}: ${e.message ?: "metadata import failed"}")
                        }
                        try {
                            val progress = checkNotNull(title.progress) { "AniList progress is unavailable" }
                            repository.updateAniListEpisodeIfHigher(
                                entryId, progress.toDouble(), initial = true, status = title.status
                            )
                            links[title.mediaId] = link.copy(initialSync = false,
                                lastProgress = title.progress, lastUpdatedAt = title.updatedAt)
                            preferences.saveAniListLinks(credentials.userId, links)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error("Could not import AniList progress", e, secrets = listOf(title.displayTitle))
                            failures.add("${title.displayTitle}: ${e.message ?: "import failed"}")
                        }
                    }
                    _state.update { it.copy(titles = titles, links = links.toMap(),
                        message = "Matched ${unique.size} AniList titles, ${failures.size} initial imports failed." +
                            if (failures.isEmpty()) "" else " ${failures.take(3).joinToString("; ")}",
                        hasError = failures.isNotEmpty()) }
                }
            }
        }
    }

    fun sync(manual: Boolean = false) {
        viewModelScope.launch {
            isRefreshing.first { !it }
            operation {
                if (!manual && (!_state.value.autoSync || !_state.value.connected || _state.value.links.isEmpty())) return@operation
                val now = SystemClock.elapsedRealtime()
                if (!manual && lastSyncAttempt?.let { now - it < 60_000L } == true) return@operation
                lastSyncAttempt = now
                val result = progressSync.sync()
                _state.update { it.copy(titles = result.titles,
                    message = "AniList: ${result.updated} updated, ${result.skipped.size} skipped, " +
                        "${result.errors.size} errors." +
                        (if (result.skipped.isEmpty()) "" else " Skipped: ${result.skipped.take(3).joinToString("; ")}.") +
                        if (result.errors.isEmpty()) "" else " ${result.errors.take(3).joinToString("; ")}",
                    hasError = result.errors.isNotEmpty() || result.skipped.isNotEmpty()) }
            }
        }
    }

    suspend fun link(title: AniListLibraryEntry, entryId: String) = operation {
        AniListProgressSync.withLinkLock {
            check(credentialsStore.read() == credentials) { "AniList account changed; try again" }
            val links = preferences.aniListLinks(credentials.userId)
            check(title.mediaId !in links && links.values.none { it.entryId == entryId }) {
                "AniList title or entry is already linked"
            }
            check(_state.value.titles.count { it.mediaId == title.mediaId } == 1) {
                "AniList title is missing or duplicated; fetch the library again"
            }
            val entry = repository.getMediaEntriesStream().first().find { it.id == entryId }
            check(entry != null && entry.type == MediaType.ANIME &&
                (entry.unitName.trim().equals("episode", true) ||
                    entry.unitName.trim().equals("episodes", true))) {
                "Select an anime entry that tracks episodes"
            }
            val updated = links + (title.mediaId to AniListLink(entryId, mediaListId = title.id))
            preferences.saveAniListLinks(credentials.userId, updated)
            _state.update { it.copy(links = updated) }
            repository.updateAniListUrl(entryId, title.sourceUrl)
            _state.update { it.copy(message = "AniList title linked. Sync now to import progress.") }
        }
    }

    suspend fun unlink(mediaId: Int, entryId: String) = operation {
        AniListProgressSync.withLinkLock {
            check(credentialsStore.read() == credentials) { "AniList account changed; try again" }
            val links = preferences.aniListLinks(credentials.userId)
            check(links[mediaId]?.entryId == entryId) { "AniList link changed; try again" }
            preferences.saveAniListLinks(credentials.userId, links - mediaId)
            _state.update { it.copy(links = links - mediaId, message = "AniList link removed.") }
        }
    }

    private suspend fun operation(block: suspend () -> Unit) = mutex.withLock {
        _state.update { it.copy(busy = true, message = "", hasError = false) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error("AniList operation failed (${e.javaClass.simpleName})")
            _state.update { it.copy(message = e.message ?: "AniList request failed", hasError = true) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    override fun onCleared() {
        preferences.removeListener(listener)
        service.close()
        super.onCleared()
    }
}
