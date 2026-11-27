package com.omnitrack.app.data

import android.util.Log
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.repository.SeriesRepository
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MangaBakaSyncResult(
    val titles: List<MangaBakaTitle>,
    val updated: Int,
    val skipped: List<String>,
    val errors: List<String>
)

class MangaBakaProgressSync(
    private val preferences: MangaBakaPreferences,
    private val service: MangaBakaService,
    private val repository: SeriesRepository
) {
    suspend fun syncMangaBakaProgress(): MangaBakaSyncResult = withContext(Dispatchers.IO) {
        withLinkLock {
            val credentials = preferences.read()
            check(credentials.accessToken.isNotBlank() && credentials.userId.isNotBlank()) {
                "Connect your MangaBaka account first"
            }
            val links = preferences.links(credentials.userId)
            check(links.isNotEmpty()) { "Link a MangaBaka title first" }
            pushPendingLocked(credentials, links)
            val entries = repository.getMediaEntriesStream().first().associateBy { it.id }
            val titles = service.library(credentials)
            val titlesById = titles.groupBy { it.series.id }
            val skipped = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var updated = 0
            for ((seriesId, link) in links) {
                val entry = entries[link.entryId]
                val candidates = titlesById[seriesId].orEmpty()
                val issue = when {
                    entry == null -> "Linked entry ${link.entryId} no longer exists"
                    entry.type == MediaType.NOVEL -> "${entry.title}: novel progress is not synced from MangaBaka"
                    entry.type != MediaType.COMIC -> "${entry.title}: MangaBaka progress requires a comic"
                    !entry.unitName.trim().equals("chapter", true) &&
                        !entry.unitName.trim().equals("chapters", true) -> "${entry.title} no longer tracks chapters"
                    candidates.size != 1 -> "MangaBaka #$seriesId is missing or duplicated in the library"
                    candidates.single().entry.seriesId != seriesId -> "MangaBaka #$seriesId has a mismatched series ID"
                    candidates.single().series.type == "novel" -> "${entry.title}: MangaBaka title is a novel"
                    candidates.single().series.type !in setOf("manga", "manhwa", "manhua", "oel", "other") ->
                        "${entry.title}: unsupported MangaBaka media type"
                    links.count { it.value.entryId == link.entryId } > 1 ->
                        "${entry.title}: multiple MangaBaka links point to this entry"
                    else -> null
                }
                if (issue != null) {
                    skipped.add(issue)
                    continue
                }
                val current = checkNotNull(entry)
                val title = candidates.single()
                val progress = title.entry.progress
                if (progress == null) {
                    skipped.add("${current.title}: MangaBaka progress is unavailable")
                    continue
                }
                if (!progress.isFinite() || progress < 0.0 || !link.offset.isFinite()) {
                    skipped.add("${current.title}: invalid chapter or offset")
                    continue
                }
                val chapter = BigDecimal.valueOf(progress).add(BigDecimal.valueOf(link.offset))
                val total = title.series.totalChapters
                if (chapter < BigDecimal.ZERO || chapter > BigDecimal("99999.99") ||
                    chapter.stripTrailingZeros().scale() > 2 ||
                    (total != null && (!total.isFinite() || total <= 0.0))
                ) {
                    skipped.add("${current.title}: invalid chapter, offset, or chapter total")
                    continue
                }
                try {
                    check(preferences.read() == credentials &&
                        preferences.links(credentials.userId)[seriesId] == link) {
                        "MangaBaka account or link changed during sync; try again"
                    }
                    if (repository.updateMangaBakaChapterIfHigher(
                            current.id, chapter.toDouble(), total != null && progress >= total,
                            title.series.status
                        )) updated++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MangaBakaSync", "Could not sync ${current.title}", e)
                    errors.add("${current.title}: ${e.message ?: "sync failed"}")
                }
            }
            MangaBakaSyncResult(titles, updated, skipped, errors)
        }
    }

    suspend fun pushPendingStatuses() = withContext(Dispatchers.IO) {
        withLinkLock {
            val credentials = preferences.read()
            if (credentials.userId.isNotBlank()) {
                pushPendingLocked(credentials, preferences.links(credentials.userId))
            }
        }
    }

    private suspend fun pushPendingLocked(credentials: MangaBakaCredentials, links: Map<Long, MangaBakaLink>) {
        repository.resolvePendingTrackerStatuses("mangabaka", links.entries.groupBy(
            { it.value.entryId }, { "${credentials.userId}:${it.key}" }
        ))
        for ((seriesId, link) in links) {
            val key = "${credentials.userId}:$seriesId"
            while (true) {
                val value = repository.pendingTrackerStatus(link.entryId, "mangabaka_status", key) ?: break
                val status = UserStatus.entries.firstOrNull { it.value == value }
                    ?: error("Invalid pending MangaBaka status: $value")
                service.updateStatus(credentials, seriesId, status)
                repository.clearPendingTrackerStatus(link.entryId, "mangabaka_status", key, value)
            }
        }
    }

    companion object {
        private val mutex = Mutex()

        suspend fun <T> withLinkLock(block: suspend () -> T): T = mutex.withLock { block() }
    }
}
