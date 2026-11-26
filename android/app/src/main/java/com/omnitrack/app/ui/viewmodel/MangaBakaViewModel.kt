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
import com.omnitrack.app.data.MangaBakaSeriesTitle
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.data.mangaBakaImportTitles
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.domain.repository.PartialMediaEntryCreateException
import com.omnitrack.app.ui.screens.settings.findMangaBakaMatch
import com.omnitrack.app.ui.screens.settings.mangaBakaMatchesType
import com.omnitrack.app.utils.ImportedMediaDraft
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.scraper.ExternalMediaScraper
import com.omnitrack.app.utils.scraper.adapters.MangaBakaAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
    val editorDraft: ImportedMediaDraft? = null,
    val batchImporting: Boolean = false,
    val batchCancelRequested: Boolean = false,
    val batchMessage: String = ""
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
        MangaBakaProgressSync.withLinkLock { withContext(Dispatchers.IO) { service.authorizationUrl() } }
    }

    suspend fun completeAuthorization(uri: Uri) = operation {
        credentials = MangaBakaProgressSync.withLinkLock { service.completeAuthorization(uri) }
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
        credentials = MangaBakaProgressSync.withLinkLock { service.connectPersonalToken(token) }
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
            imported.copy(draft = imported.draft.copy(
                userStatus = status,
                currentUnit = if (imported.draft.type == MediaType.NOVEL) imported.draft.currentUnit else progress
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MangaBaka", "Could not load library entry for import", e)
            imported.copy(warning = "MangaBaka library: ${e.message ?: "Could not load progress"}")
        }
    }

    suspend fun linkImportedSeries(seriesId: Long, entryId: String, unitName: String, sourceUrl: String) = mutex.withLock {
      MangaBakaProgressSync.withLinkLock {
        credentials = withContext(Dispatchers.IO) { preferences.read() }
        check(credentials.accessToken.isNotBlank() && credentials.userId.isNotBlank()) {
            "Connect MangaBaka in Settings to link this entry"
        }
        check(unitName.trim().equals("chapter", true) || unitName.trim().equals("chapters", true)) {
            "MangaBaka links require chapter-based entries"
        }
        withContext(Dispatchers.IO) { repository.fetchFromSupabase() }
        val entries = repository.getMediaEntriesStream().first()
        val entry = entries.firstOrNull { it.id == entryId }
        check(entry != null) {
            "The new entry could not be loaded for MangaBaka linking"
        }
        check(entry.type == MediaType.NOVEL || entry.type == MediaType.COMIC) {
            "MangaBaka links require a novel or comic"
        }
        check(entry.unitName.trim().equals("chapter", true) || entry.unitName.trim().equals("chapters", true)) {
            "MangaBaka links require chapter-based entries"
        }
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        val existingLink = links[seriesId]
        if (existingLink != null && existingLink.entryId != entryId) {
            check(entries.none { it.id == existingLink.entryId }) {
                "This MangaBaka title is already linked to another entry"
            }
        }
        check(links.none { (id, link) -> id != seriesId && link.entryId == entryId }) {
            "This entry is already linked to a MangaBaka title"
        }
        val remote = service.libraryEntryOrAdd(credentials, seriesId)
        check(remote.seriesId == seriesId) { "MangaBaka series ID does not match" }
        withContext(Dispatchers.IO) { repository.updateMangaBakaUrl(entryId, sourceUrl) }
        check(preferences.read() == credentials && preferences.links(credentials.userId) == links) {
            "MangaBaka account or links changed; try again"
        }
        val latest = repository.getMediaEntriesStream().first().firstOrNull { it.id == entryId }
        check(latest != null && latest.type == entry.type && latest.unitName == entry.unitName) {
            "The entry changed during MangaBaka linking; try again"
        }
        saveLink(seriesId, entryId, existingLink?.takeIf { it.entryId == entryId }?.offset ?: 0.0)
      }
    }

    suspend fun fetch() = operation {
        val titles = service.library(credentials)
        _state.value = _state.value.copy(
            titles = titles,
            message = "Fetched ${titles.size} MangaBaka titles."
        )
    }

    suspend fun syncAndMatchLibrary() = operation {
        check(_state.value.connected) { "Connect your MangaBaka account first" }
        val titles = service.library(credentials)
        check(titles.all { it.entry.seriesId == it.series.id }) { "MangaBaka series ID does not match" }
        _state.update { it.copy(titles = titles, message = "Matching MangaBaka library...") }
        val entries = repository.getMediaEntriesStream().first()
        val entryIds = entries.map { it.id }.toSet()
        val links = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
        val activeLinks = links.filterValues { it.entryId in entryIds }
        val linkedEntryIds = activeLinks.values.map { it.entryId }.toSet()
        val availableEntries = entries.filter {
            it.id !in linkedEntryIds &&
                (it.unitName.trim().equals("chapter", true) || it.unitName.trim().equals("chapters", true))
        }
        val matches = withContext(Dispatchers.Default) {
            titles.mapNotNull { title ->
                coroutineContext.ensureActive()
                if (title.series.id in activeLinks) null
                else findMangaBakaMatch(title, availableEntries)?.let { title to it.id }
            }
        }
        var linked = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        matches.forEachIndexed { index, (title, entryId) ->
            coroutineContext.ensureActive()
            try {
                check(withContext(Dispatchers.IO) { preferences.read() } == credentials) {
                    "MangaBaka account changed; try again"
                }
                val currentEntries = repository.getMediaEntriesStream().first()
                val currentIds = currentEntries.map { it.id }.toSet()
                val currentLinks = withContext(Dispatchers.IO) { preferences.links(credentials.userId) }
                val existing = currentLinks[title.series.id]
                val currentEntry = currentEntries.firstOrNull { it.id == entryId }
                if (currentEntry == null || findMangaBakaMatch(title, listOf(currentEntry)) == null ||
                    currentLinks.values.any { it.entryId == entryId } ||
                    (existing != null && existing.entryId in currentIds)
                ) {
                    skipped++
                } else {
                    linkEntry(title, entryId, 0.0, replaceMissing = true)
                    linked++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MangaBaka", "Could not link ${title.title}", e)
                failures.add("${title.title}: ${e.message ?: "Unknown error"}")
            }
            _state.update {
                it.copy(message = "Sync & match: ${matches.size} matches found, $linked linked " +
                    "(${index + 1}/${matches.size} processed).")
            }
        }
        val details = if (failures.isEmpty()) "" else " Failures: ${failures.joinToString("; ")}"
        _state.update {
            it.copy(message = "Sync & match: ${matches.size} matches found, $linked linked, " +
                "$skipped skipped, ${failures.size} failed.$details")
        }
    }

    suspend fun sync(manual: Boolean) = operation {
        if (!manual && (!_state.value.connected || _state.value.links.isEmpty())) return@operation
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastSyncAttempt?.let { now - it < 60_000L } == true) return@operation
        lastSyncAttempt = now
        val result = progressSync.syncMangaBakaProgress()
        val details = (result.skipped + result.errors).joinToString("; ")
        _state.value = _state.value.copy(
            titles = result.titles,
            message = if (result.updated == 0 && result.skipped.isEmpty() && result.errors.isEmpty())
                "MangaBaka progress is up to date."
            else "MangaBaka: ${result.updated} updated, ${result.skipped.size} skipped, " +
                "${result.errors.size} errors.${if (details.isEmpty()) "" else " $details"}"
        )
    }

    suspend fun add(title: MangaBakaTitle, otherType: String? = null) = operation {
        val imported = hydrateTitle(title, otherType)
        _state.update { it.copy(editorDraft = imported) }
    }

    private suspend fun hydrateTitle(title: MangaBakaTitle, otherType: String? = null): ImportedMediaDraft {
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
            val (mainTitle, alternativeTitles) = mangaBakaImportTitles(title.series.titles.orEmpty(), title.title)
            ImportedMediaDraft(
                sourceUrl = seriesUrl,
                normalizedUrl = seriesUrl,
                adapterName = "MangaBaka",
                draft = ScrapedMediaDraft(
                    title = mainTitle,
                    alternativeTitles = alternativeTitles,
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
        return imported.copy(
            normalizedUrl = seriesUrl,
            draft = imported.draft.copy(
                userStatus = status,
                currentUnit = if (imported.draft.type == MediaType.NOVEL) imported.draft.currentUnit else progress,
                totalChapters = imported.draft.totalChapters ?: title.series.totalChapters
            )
        )
    }

    fun consumeEditorDraft() {
        _state.update { it.copy(editorDraft = null) }
    }

    fun cancelBatchImport() {
        _state.update { if (it.batchImporting) it.copy(batchCancelRequested = true) else it }
    }

    suspend fun batchImport(titles: List<MangaBakaTitle>) = operation {
        _state.update {
            it.copy(batchImporting = true, batchCancelRequested = false,
                batchMessage = "Batch import: 0 / ${titles.size} imported (0 processed).")
        }
        var imported = 0
        var processed = 0
        var skipped = 0
        val reviews = mutableListOf<String>()
        val failures = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                for (title in titles) {
                    if (_state.value.batchCancelRequested) break
                    if (_state.value.links.containsKey(title.series.id)) {
                        skipped++
                    } else {
                        try {
                            val review = importNewTitle(title)
                            if (review == null) imported++ else reviews.add("${title.title}: $review")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("MangaBaka", "Could not import ${title.title}", e)
                            failures.add("${title.title}: ${e.message ?: "Unknown error"}")
                        }
                    }
                    processed++
                    _state.update {
                        it.copy(batchMessage = "Batch import: $imported / ${titles.size} imported " +
                            "($processed processed, $skipped skipped, ${reviews.size} need review, ${failures.size} failed).")
                    }
                }
            }
        } finally {
            val cancelled = _state.value.batchCancelRequested || processed < titles.size
            val reviewDetails = if (reviews.isEmpty()) "" else " Needs review: ${reviews.joinToString("; ")}."
            val details = if (failures.isEmpty()) "" else " Failures: ${failures.joinToString("; ")}"
            _state.update {
                it.copy(
                    batchImporting = false,
                    batchCancelRequested = false,
                    batchMessage = "Batch import ${if (cancelled) "cancelled" else "finished"}: " +
                        "$imported / ${titles.size} imported, $skipped skipped, ${reviews.size} need review, " +
                        "${failures.size} failed.$reviewDetails$details"
                )
            }
        }
    }

    private suspend fun importNewTitle(title: MangaBakaTitle): String? {
        findBatchDuplicate(title)?.let { return "Possible existing entry '${it.title}'; link or review manually" }
        if (title.series.type == "other") return "Choose novel or comic using Add as new"
        val imported = hydrateTitle(title)
        val draft = imported.draft
        check(draft.title.isNotBlank()) { "Title is missing" }
        findBatchDuplicate(title, draft)?.let { return "Possible existing entry '${it.title}'; link or review manually" }
        if (draft.creators.none { it.name.isNotBlank() }) {
            return "${imported.warning?.let { "$it " }.orEmpty()}" +
                "Creator metadata is missing; use Add as new to supply a creator"
        }
        check(draft.unitName.trim().equals("chapter", true) || draft.unitName.trim().equals("chapters", true)) {
            "MangaBaka links require chapter-based entries"
        }
        check(mangaBakaSeriesId(draft.sourceUrl) != null) { "Invalid MangaBaka source URL" }
        val authors = repository.getAuthors().toMutableList()
        val creators = draft.creators.filter { it.name.isNotBlank() }.map { creator ->
            val name = creator.name.trim()
            val author = authors.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                ?: repository.createAuthor(name, null, null, null).also { authors.add(it) }
            author.id to creator.role
        }
        val platformName = draft.platformHint?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Platform metadata is missing")
        val platform = repository.getPlatforms().firstOrNull {
            it.name.replace(" ", "").trim().equals(platformName.replace(" ", "").trim(), ignoreCase = true)
        } ?: repository.createPlatform(CreatePlatformRequest(name = platformName))
        val seriesId = draft.seriesTitle?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            repository.getSeries().firstOrNull { it.title.trim().equals(name, ignoreCase = true) }?.id
                ?: repository.createSeries(CreateSeriesRequest(title = name)).id
        }
        check(preferences.read() == credentials && credentials.userId.isNotBlank() &&
            credentials.accessToken.isNotBlank()) { "MangaBaka account changed; reconnect and try again" }
        check(!preferences.links(credentials.userId).containsKey(title.series.id)) {
            "This MangaBaka title is already linked"
        }
        findBatchDuplicate(title, draft)?.let { return "Possible existing entry '${it.title}'; link or review manually" }
        val created = try {
            repository.createMediaEntry(
                CreateEntryRequest(
                    seriesId = seriesId,
                    title = draft.title.trim(),
                    type = draft.type.value,
                    sequenceNumber = draft.sequenceNumber,
                    userStatus = draft.userStatus ?: "planning",
                    publishingStatus = draft.publishingStatus.ifBlank { "releasing" },
                    currentUnit = draft.currentUnit ?: 0.0,
                    unitName = draft.unitName,
                    sourceUrl = draft.sourceUrl,
                    platformId = platform.id,
                    coverUrl = draft.coverUrl
                ),
                creators,
                draft.alternativeTitles
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: PartialMediaEntryCreateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Entry may have been created; check the library before retrying: ${e.message}", e)
        }
        try {
          MangaBakaProgressSync.withLinkLock {
            check(preferences.read() == credentials &&
                !preferences.links(credentials.userId).containsKey(title.series.id)) {
                "MangaBaka account or link changed"
            }
            repository.updateMangaBakaUrl(created.id, draft.sourceUrl)
            check(preferences.read() == credentials &&
                !preferences.links(credentials.userId).containsKey(title.series.id)) {
                "MangaBaka account or link changed"
            }
            saveLink(title.series.id, created.id, 0.0)
          }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Entry ${created.id} was created but linking failed; link it manually: ${e.message}", e
            )
        }
        return null
    }

    private suspend fun findBatchDuplicate(title: MangaBakaTitle, draft: ScrapedMediaDraft? = null): MediaEntry? {
        val hydratedId = draft?.sourceUrl?.let(::mangaBakaSeriesId)
        val hydratedTitle = draft?.let {
            title.copy(series = title.series.copy(
                type = if (it.type == MediaType.NOVEL) "novel" else "manga",
                titles = title.series.titles.orEmpty() +
                    (listOf(it.title) + it.alternativeTitles).map { name -> MangaBakaSeriesTitle(name, "en") }
            ))
        }
        return repository.getMediaEntriesStream().first().firstOrNull { entry ->
            val sourceId = entry.sourceUrl?.let(::mangaBakaSeriesId)
            sourceId != null && (sourceId == title.series.id || sourceId == hydratedId) ||
                findMangaBakaMatch(title, listOf(entry)) != null ||
                hydratedTitle != null && findMangaBakaMatch(hydratedTitle, listOf(entry)) != null
        }
    }

    suspend fun link(title: MangaBakaTitle, entryId: String, offset: Double) = operation {
        val entryTitle = linkEntry(title, entryId, offset)
        _state.update { it.copy(message = "Linked ${title.title} to $entryTitle.") }
    }

    suspend fun linkAllMatched(matches: List<Pair<MangaBakaTitle, String>>) = operation {
        withContext(Dispatchers.IO) {
            var linked = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            _state.update { it.copy(message = "Linking matches: 0/${matches.size} processed.") }
            matches.forEachIndexed { index, (title, entryId) ->
                if (_state.value.links.containsKey(title.series.id) ||
                    _state.value.links.values.any { it.entryId == entryId }
                ) {
                    skipped++
                } else {
                    try {
                        linkEntry(title, entryId, 0.0)
                        linked++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("MangaBaka", "Could not link ${title.title}", e)
                        failures.add("${title.title}: ${e.message ?: "Unknown error"}")
                    }
                }
                _state.update {
                    it.copy(message = "Linking matches: ${index + 1}/${matches.size} processed " +
                        "($linked linked, $skipped skipped, ${failures.size} failed).")
                }
            }
            val details = if (failures.isEmpty()) "" else " Failures: ${failures.joinToString("; ")}"
            _state.update {
                it.copy(message = "Link all matched: $linked linked, $skipped skipped, ${failures.size} failed.$details")
            }
        }
    }

    private suspend fun linkEntry(
        title: MangaBakaTitle, entryId: String, offset: Double, replaceMissing: Boolean = false
    ): String = MangaBakaProgressSync.withLinkLock {
        check(title.entry.seriesId == title.series.id) { "MangaBaka series ID does not match" }
        check(offset.isFinite()) { "Enter a valid chapter offset" }
        check(preferences.read() == credentials && credentials.userId.isNotBlank() &&
            credentials.accessToken.isNotBlank()) { "MangaBaka account changed; reconnect and try again" }
        val links = preferences.links(credentials.userId)
        val entries = repository.getMediaEntriesStream().first()
        val existing = links[title.series.id]
        check(existing == null || (replaceMissing && entries.none { it.id == existing.entryId })) {
            "This MangaBaka title is already linked"
        }
        val entry = entries.firstOrNull { it.id == entryId } ?: error("Entry no longer exists")
        check(mangaBakaMatchesType(title, entry.type)) {
            "Choose a matching novel or comic"
        }
        check(entry.unitName.trim().equals("chapter", true) || entry.unitName.trim().equals("chapters", true)) {
            "Choose an entry that tracks chapters"
        }
        check(links.values.none { it.entryId == entryId }) {
            "This entry is already linked to a MangaBaka title"
        }
        withContext(Dispatchers.IO) { repository.updateMangaBakaUrl(entryId, mangaBakaSeriesUrl(title)) }
        try {
            check(preferences.read() == credentials && preferences.links(credentials.userId) == links) {
                "MangaBaka account or links changed; try again"
            }
            val latest = repository.getMediaEntriesStream().first().firstOrNull { it.id == entryId }
            check(latest != null && latest.type == entry.type && latest.unitName == entry.unitName) {
                "The entry changed during MangaBaka linking; try again"
            }
            saveLink(title.series.id, entryId, offset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Updated ${entry.title}'s URL, but its MangaBaka link could not be saved", e)
        }
        entry.title
    }

    suspend fun unlink(title: MangaBakaTitle) = operation {
        MangaBakaProgressSync.withLinkLock {
            check(preferences.read() == credentials) { "MangaBaka account changed; try again" }
            withContext(Dispatchers.IO) { preferences.unlink(credentials.userId, title.series.id) }
        }
        _state.value = _state.value.copy(
            links = _state.value.links - title.series.id,
            message = "Unlinked ${title.title}."
        )
    }

    private suspend fun saveLink(seriesId: Long, entryId: String, offset: Double) {
        withContext(Dispatchers.IO) { preferences.link(credentials.userId, seriesId, entryId, offset) }
        _state.update { it.copy(links = it.links + (seriesId to MangaBakaLink(entryId, offset))) }
    }

    private suspend fun operation(block: suspend () -> Unit) = mutex.withLock {
        _state.update { it.copy(busy = true, message = "") }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MangaBaka", "MangaBaka operation failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(message = e.message ?: "MangaBaka request failed") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun close() {
        preferences.unregisterLinkListener(linkListener)
        service.close()
    }
}
