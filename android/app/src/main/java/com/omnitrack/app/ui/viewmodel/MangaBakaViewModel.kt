package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.omnitrack.app.data.MangaBakaCredentials
import com.omnitrack.app.data.MangaBakaLink
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.MangaBakaProgressSync
import com.omnitrack.app.data.MangaBakaService
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.utils.ImportedMediaDraft
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.ExternalMediaScraper
import com.omnitrack.app.utils.scraper.adapters.MangaBakaAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun mangaBakaSeriesUrl(title: MangaBakaTitle): String {
    val uri = Uri.parse(title.series.url)
    return if (uri.scheme == "https" && uri.host == "mangabaka.org" &&
        uri.port == -1 && uri.userInfo == null && !uri.path.isNullOrBlank()
    ) title.series.url else "https://mangabaka.org/series/${title.series.id}"
}

fun mangaBakaSeriesId(url: String): Long? = MangaBakaAdapter.seriesId(url)

data class MangaBakaState(
    val initialized: Boolean = false,
    val username: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val titles: List<MangaBakaTitle> = emptyList(),
    val links: Map<Long, MangaBakaLink> = emptyMap(),
    val editorDraft: ImportedMediaDraft? = null
)

class MangaBakaViewModel(context: Context, private val repository: SeriesRepository) {
    private val appContext = context.applicationContext
    private val preferences = MangaBakaPreferences(appContext)
    private val service = MangaBakaService(preferences)
    private val progressSync = MangaBakaProgressSync(preferences, service, repository)
    private val _state = MutableStateFlow(MangaBakaState())
    val state = _state.asStateFlow()
    private var credentials = MangaBakaCredentials()
    private val mutex = Mutex()
    private var lastSyncAttempt: Long? = null
    private val linkListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val links = preferences.links(credentials.userId)
        _state.update { it.copy(links = links) }
    }

    init {
        preferences.registerLinkListener(linkListener)
    }

    suspend fun initialize() = operation {
        credentials = withContext(Dispatchers.IO) { preferences.read() }
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        _state.value = _state.value.copy(
            initialized = true,
            username = credentials.username,
            connected = credentials.accessToken.isNotBlank() && credentials.userId.isNotBlank(),
            links = links
        )
    }

    suspend fun authorizationUrl(): String = mutex.withLock {
        withContext(Dispatchers.IO) { service.authorizationUrl() }
    }

    suspend fun completeAuthorization(uri: Uri) = operation {
        credentials = service.completeAuthorization(uri)
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        _state.value = _state.value.copy(
            username = credentials.username,
            connected = true,
            titles = emptyList(),
            links = links,
            message = "MangaBaka connected. Fetch your library to browse titles."
        )
    }

    suspend fun connectPersonalToken(token: String) = operation {
        credentials = service.connectPersonalToken(token)
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        _state.value = _state.value.copy(
            username = credentials.username,
            connected = true,
            titles = emptyList(),
            links = links,
            message = "MangaBaka connected. Fetch your library to browse titles."
        )
    }

    suspend fun prepareImportedDraft(imported: ImportedMediaDraft): ImportedMediaDraft {
        val seriesId = mangaBakaSeriesId(imported.normalizedUrl) ?: return imported
        return try {
            val saved = withContext(Dispatchers.IO) { preferences.read() }
            if (saved.accessToken.isBlank() || saved.userId.isBlank()) {
                return imported.copy(warning = "Connect MangaBaka in Settings to link this entry.")
            }
            val remote = service.libraryEntry(saved, seriesId) ?: return imported
            check(remote.seriesId == seriesId) { "MangaBaka series ID does not match" }
            val progress = remote.progress
            check(progress == null || (progress.isFinite() && progress in 0.0..99999.99 &&
                java.math.BigDecimal.valueOf(progress).stripTrailingZeros().scale() <= 2)) {
                "MangaBaka chapter progress cannot be represented in OmniTrack"
            }
            val status = when (remote.state) {
                "reading", "rereading" -> "in_progress"
                "completed" -> "completed"
                "paused" -> "paused"
                "dropped" -> "dropped"
                "considering", "plan_to_read" -> "planning"
                else -> error("Unsupported MangaBaka reading state: ${remote.state}")
            }
            imported.copy(draft = imported.draft.copy(userStatus = status, currentUnit = progress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MangaBaka", "Could not load library entry for import", e)
            imported.copy(warning = "MangaBaka library: ${e.message ?: "Could not load progress"}")
        }
    }

    suspend fun linkImportedSeries(seriesId: Long, entryId: String, unitName: String, sourceUrl: String) = mutex.withLock {
        credentials = withContext(Dispatchers.IO) { preferences.read() }
        check(credentials.accessToken.isNotBlank() && credentials.userId.isNotBlank()) {
            "Connect MangaBaka in Settings to link this entry"
        }
        check(unitName.trim().equals("chapter", true) || unitName.trim().equals("chapters", true)) {
            "MangaBaka links require chapter-based entries"
        }
        withContext(Dispatchers.IO) { repository.fetchFromSupabase() }
        val entries = repository.getMediaEntriesStream().first()
        check(entries.any { it.id == entryId }) {
            "The new entry could not be loaded for MangaBaka linking"
        }
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        val existingLink = links[seriesId]
        if (existingLink != null && existingLink.entryId != entryId) {
            check(entries.none { it.id == existingLink.entryId }) {
                "This MangaBaka title is already linked to another entry"
            }
            withContext(Dispatchers.IO) { preferences.unlink(credentials.userId, seriesId) }
            _state.update { it.copy(links = it.links - seriesId) }
        }
        check(links.none { (id, link) -> id != seriesId && link.entryId == entryId }) {
            "This entry is already linked to a MangaBaka title"
        }
        val remote = service.libraryEntryOrAdd(credentials, seriesId)
        check(remote.seriesId == seriesId) { "MangaBaka series ID does not match" }
        withContext(Dispatchers.IO) { repository.updateMangaBakaUrl(entryId, sourceUrl) }
        saveLink(seriesId, entryId, existingLink?.takeIf { it.entryId == entryId }?.offset ?: 0.0)
    }

    suspend fun fetch() = operation {
        val titles = service.library(credentials)
        _state.value = _state.value.copy(
            titles = titles,
            message = "Fetched ${titles.size} MangaBaka titles."
        )
    }

    suspend fun sync(manual: Boolean) = operation {
        if (!manual && (!_state.value.connected || _state.value.links.isEmpty())) return@operation
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastSyncAttempt?.let { now - it < 60_000L } == true) return@operation
        lastSyncAttempt = now
        val (titles, updated) = progressSync.syncMangaBakaProgress()
        _state.value = _state.value.copy(
            titles = titles,
            message = if (updated == 0) "MangaBaka progress is up to date."
                else "Synced progress for $updated MangaBaka ${if (updated == 1) "title" else "titles"}."
        )
    }

    suspend fun add(title: MangaBakaTitle, otherType: String? = null) = operation {
        check(title.entry.seriesId == title.series.id) { "MangaBaka series ID does not match" }
        check(_state.value.links[title.series.id] == null) { "This MangaBaka title is already linked" }
        val progress = title.entry.progress ?: 0.0
        check(progress.isFinite() && progress in 0.0..99999.99 &&
            java.math.BigDecimal.valueOf(progress).stripTrailingZeros().scale() <= 2) {
            "MangaBaka chapter progress cannot be represented in OmniTrack"
        }
        val status = when (title.entry.state) {
            "reading", "rereading" -> "in_progress"
            "completed" -> "completed"
            "paused" -> "paused"
            "dropped" -> "dropped"
            "considering", "plan_to_read" -> "planning"
            else -> error("Unsupported MangaBaka reading state: ${title.entry.state}")
        }
        val selectedType = when (otherType) {
            "novel" -> MediaType.NOVEL
            "comic" -> MediaType.COMIC
            null -> null
            else -> error("Choose whether this title is a novel or comic")
        }
        val seriesUrl = "https://mangabaka.org/series/${title.series.id}"
        check(mangaBakaSeriesId(seriesUrl) == title.series.id) { "Invalid MangaBaka series ID" }
        val imported = try {
            ExternalMediaScraper.scrape(appContext, seriesUrl, selectedType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MangaBaka", "Could not load full series metadata", e)
            val type = when (title.series.type) {
                "novel" -> MediaType.NOVEL
                "manga", "manhwa", "manhua", "oel" -> MediaType.COMIC
                "other" -> selectedType ?: error("Choose whether this title is a novel or comic")
                else -> error("Unsupported MangaBaka media type: ${title.series.type}")
            }
            val publishingStatus = when (title.series.status) {
                "upcoming" -> "announced"
                "releasing", "unknown" -> "releasing"
                "hiatus" -> "hiatus"
                "completed" -> "completed"
                "cancelled" -> "discontinued"
                else -> error("Unsupported MangaBaka publication status: ${title.series.status}")
            }
            ImportedMediaDraft(
                sourceUrl = seriesUrl,
                normalizedUrl = seriesUrl,
                adapterName = "MangaBaka",
                draft = ScrapedMediaDraft(
                    title = title.title,
                    alternativeTitles = title.series.titles.orEmpty().map { it.title }
                        .filterNot { it.equals(title.title, ignoreCase = true) }
                        .distinctBy { it.lowercase() },
                    type = type,
                    platformHint = "MangaBaka",
                    publishingStatus = publishingStatus,
                    coverUrl = title.series.cover?.x250 ?: title.series.cover?.raw,
                    sourceUrl = mangaBakaSeriesUrl(title),
                    totalChapters = title.series.totalChapters
                ),
                warning = "Could not load full MangaBaka metadata; review the library details."
            )
        }
        _state.update {
            it.copy(editorDraft = imported.copy(
                normalizedUrl = seriesUrl,
                draft = imported.draft.copy(
                    userStatus = status,
                    currentUnit = progress,
                    totalChapters = imported.draft.totalChapters ?: title.series.totalChapters
                )
            ))
        }
    }

    fun consumeEditorDraft() {
        _state.update { it.copy(editorDraft = null) }
    }

    suspend fun link(title: MangaBakaTitle, entryId: String, offset: Double) = operation {
        check(title.entry.seriesId == title.series.id) { "MangaBaka series ID does not match" }
        check(offset.isFinite()) { "Enter a valid chapter offset" }
        check(_state.value.links[title.series.id] == null) { "This MangaBaka title is already linked" }
        val entries = repository.getMediaEntriesStream().first()
        val entry = entries.firstOrNull { it.id == entryId } ?: error("Entry no longer exists")
        check(entry.unitName.trim().equals("chapter", true) || entry.unitName.trim().equals("chapters", true)) {
            "Choose an entry that tracks chapters"
        }
        check(_state.value.links.values.none { it.entryId == entryId }) {
            "This entry is already linked to a MangaBaka title"
        }
        withContext(Dispatchers.IO) { repository.updateMangaBakaUrl(entryId, mangaBakaSeriesUrl(title)) }
        try {
            saveLink(title.series.id, entryId, offset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Updated ${entry.title}'s URL, but its MangaBaka link could not be saved", e)
        }
        _state.value = _state.value.copy(message = "Linked ${title.title} to ${entry.title}.")
    }

    suspend fun unlink(title: MangaBakaTitle) = operation {
        withContext(Dispatchers.IO) { preferences.unlink(credentials.userId, title.series.id) }
        _state.value = _state.value.copy(
            links = _state.value.links - title.series.id,
            message = "Unlinked ${title.title}."
        )
    }

    private suspend fun saveLink(seriesId: Long, entryId: String, offset: Double) {
        withContext(Dispatchers.IO) { preferences.link(credentials.userId, seriesId, entryId, offset) }
        _state.value = _state.value.copy(links = _state.value.links + (seriesId to MangaBakaLink(entryId, offset)))
    }

    private suspend fun operation(block: suspend () -> Unit) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = "")
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MangaBaka", "MangaBaka operation failed: ${e.javaClass.simpleName}")
            _state.value = _state.value.copy(message = e.message ?: "MangaBaka request failed")
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun close() {
        preferences.unregisterLinkListener(linkListener)
        service.close()
    }
}
