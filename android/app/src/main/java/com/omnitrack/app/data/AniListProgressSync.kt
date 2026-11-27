package com.omnitrack.app.data

import android.util.Log
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AniListSyncResult(
    val titles: List<AniListLibraryEntry>,
    val updated: Int,
    val skipped: List<String>,
    val errors: List<String>
)

class AniListProgressSync(
    private val credentialsStore: AniListPreferences,
    private val preferences: OmniTrackPreferences,
    private val service: AniListService,
    private val repository: SeriesRepository
) {
    suspend fun sync(): AniListSyncResult = withContext(Dispatchers.IO) {
        withLinkLock {
            val credentials = credentialsStore.read()
            check(credentials.accessToken.isNotBlank() && credentials.userId > 0) { "Connect AniList first" }
            val links = preferences.aniListLinks(credentials.userId)
            check(links.isNotEmpty()) { "Link an AniList title first" }
            pushPendingLocked(credentials, links)
            val entries = repository.getMediaEntriesStream().first().associateBy { it.id }
            val titles = service.library(credentials)
            val remote = titles.groupBy { it.mediaId }
            val skipped = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var updated = 0
            for ((mediaId, link) in links) {
                val entry = entries[link.entryId]
                val candidates = remote[mediaId].orEmpty()
                val issue = when {
                    entry == null -> "Linked entry ${link.entryId} no longer exists"
                    entry.type != MediaType.ANIME -> "${entry.title}: AniList requires an anime entry"
                    !entry.unitName.trim().equals("episode", true) &&
                        !entry.unitName.trim().equals("episodes", true) -> "${entry.title} no longer tracks episodes"
                    links.count { it.value.entryId == link.entryId } > 1 -> "${entry.title} has multiple AniList links"
                    candidates.size != 1 -> "AniList #$mediaId is missing or duplicated in the library"
                    else -> null
                }
                if (issue != null) {
                    skipped.add(issue)
                    continue
                }
                try {
                    check(credentialsStore.read() == credentials &&
                        preferences.aniListLinks(credentials.userId)[mediaId] == link) {
                        "AniList account or link changed during sync; try again"
                    }
                    val title = candidates.single()
                    val urlChanged = try {
                        repository.updateAniListUrl(link.entryId, title.sourceUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("AniListSync", "Could not sync AniList URL", e)
                        errors.add("${entry?.title}: ${e.message ?: "URL sync failed"}")
                        false
                    }
                    val metadataChanged = try {
                        repository.updateAniListMetadata(
                            link.entryId, title.media.coverImage?.large, title.media.episodes
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("AniListSync", "Could not sync AniList metadata", e)
                        errors.add("${entry?.title}: ${e.message ?: "metadata sync failed"}")
                        false
                    }
                    val progress = title.progress
                    if (progress == null) {
                        if (urlChanged || metadataChanged) updated++
                        skipped.add("${entry?.title}: AniList progress is unavailable")
                        continue
                    }
                    val progressChanged = repository.updateAniListEpisodeIfHigher(
                            link.entryId, progress.toDouble(), link.initialSync, title.status
                        )
                    if (urlChanged || metadataChanged || progressChanged) {
                        updated++
                    }
                    val currentLinks = preferences.aniListLinks(credentials.userId)
                    preferences.saveAniListLinks(credentials.userId, currentLinks + (mediaId to link.copy(
                        initialSync = false, mediaListId = title.id, lastProgress = title.progress,
                        lastUpdatedAt = title.updatedAt
                    )))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AniListSync", "Could not sync AniList entry", e)
                    errors.add("${entry?.title}: ${e.message ?: "sync failed"}")
                }
            }
            AniListSyncResult(titles, updated, skipped, errors)
        }
    }

    suspend fun pushPendingStatuses() = withContext(Dispatchers.IO) {
        withLinkLock {
            val credentials = credentialsStore.read()
            if (credentials.userId > 0) {
                pushPendingLocked(credentials, preferences.aniListLinks(credentials.userId))
            }
        }
    }

    private suspend fun pushPendingLocked(credentials: AniListCredentials, links: Map<Int, AniListLink>) {
        repository.resolvePendingTrackerStatuses("anilist", links.entries.groupBy(
            { it.value.entryId }, { "${credentials.userId}:${it.key}" }
        ))
        for ((mediaId, link) in links) {
            val key = "${credentials.userId}:$mediaId"
            while (true) {
                val value = repository.pendingTrackerStatus(link.entryId, "anilist_status", key) ?: break
                val status = UserStatus.entries.firstOrNull { it.value == value }
                    ?: error("Invalid pending AniList status: $value")
                service.updateStatus(credentials, mediaId, status)
                repository.clearPendingTrackerStatus(link.entryId, "anilist_status", key, value)
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        suspend fun <T> withLinkLock(block: suspend () -> T): T = mutex.withLock { block() }
    }
}
