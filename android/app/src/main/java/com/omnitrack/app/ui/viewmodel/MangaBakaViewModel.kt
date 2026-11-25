package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.omnitrack.app.data.MangaBakaCredentials
import com.omnitrack.app.data.MangaBakaLink
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.MangaBakaService
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MangaBakaState(
    val username: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val titles: List<MangaBakaTitle> = emptyList(),
    val links: Map<Long, MangaBakaLink> = emptyMap()
)

class MangaBakaViewModel(context: Context, private val repository: SeriesRepository) {
    private val preferences = MangaBakaPreferences(context.applicationContext)
    private val service = MangaBakaService(preferences)
    private val _state = MutableStateFlow(MangaBakaState())
    val state = _state.asStateFlow()
    private var credentials = MangaBakaCredentials()
    private val mutex = Mutex()

    suspend fun initialize() = operation {
        credentials = withContext(Dispatchers.IO) { preferences.read() }
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        _state.value = _state.value.copy(
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

    suspend fun fetch() = operation {
        val titles = service.library(credentials)
        _state.value = _state.value.copy(
            titles = titles,
            message = "Fetched ${titles.size} MangaBaka titles."
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
        val publishingStatus = when (title.series.status) {
            "upcoming" -> "announced"
            "releasing", "unknown" -> "releasing"
            "hiatus" -> "hiatus"
            "completed" -> "completed"
            "cancelled" -> "discontinued"
            else -> error("Unsupported MangaBaka publication status: ${title.series.status}")
        }
        val type = when (title.series.type) {
            "novel" -> "novel"
            "manga", "manhwa", "manhua", "oel" -> "comic"
            "other" -> otherType?.takeIf { it == "novel" || it == "comic" }
                ?: error("Choose whether this title is a novel or comic")
            else -> error("Unsupported MangaBaka media type: ${title.series.type}")
        }
        val entry = withContext(Dispatchers.IO) {
            repository.createMangaBakaEntry(
                title.title, type, status, publishingStatus, progress,
                title.series.cover?.x250 ?: title.series.cover?.raw, title.series.url
            )
        }
        try {
            saveLink(title.series.id, entry.id, 0.0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Entry ${entry.title} was created, but its MangaBaka link could not be saved", e)
        }
        _state.value = _state.value.copy(message = "Added and linked ${entry.title}.")
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
        saveLink(title.series.id, entryId, offset)
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

    fun close() = service.close()
}
