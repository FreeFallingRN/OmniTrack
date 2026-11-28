package com.omnitrack.app.data

import android.content.Context
import android.os.SystemClock
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.utils.MoonReaderSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class TrackerSyncSourceResult(
    val name: String,
    val updated: Int,
    val skipped: Int,
    val errors: Int,
    val details: List<String>,
    val filesScanned: Int = 0,
    val filesSkipped: Int = 0,
    val filesMatched: Int = 0,
    val unmatchedItems: List<String> = emptyList(),
    val reviewItems: List<String> = emptyList()
)

enum class TrackerSyncTrigger(val value: String) {
    PERIODIC("periodic"),
    PULL("pull")
}

data class TrackerSyncSummary(val sources: List<TrackerSyncSourceResult>) {
    val updated: Int get() = sources.sumOf { it.updated }
    val skipped: Int get() = sources.sumOf { it.skipped }
    val errors: Int get() = sources.sumOf { it.errors }
    val hasIssues: Boolean get() = skipped > 0 || errors > 0
    val hasErrors: Boolean get() = errors > 0

    fun toToastMessage(): String = if (sources.isEmpty()) {
        "No connected sync sources."
    } else {
        sources.joinToString("; ") { source ->
            "${source.name}: ${source.updated} updated, ${source.skipped} skipped, ${source.errors} errors" +
                source.details.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty()
        }
    }

    fun issueMessage(): String? = sources
        .filter { it.skipped > 0 || it.errors > 0 }
        .flatMap { source -> source.details.map { "${source.name}: $it" } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ")
}

class TrackerSyncCoordinator(
    context: Context,
    private val repository: SeriesRepository
) {
    private val context = context.applicationContext

    suspend fun sync(trigger: TrackerSyncTrigger): TrackerSyncSummary = syncFlight.run {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val summary = withContext(Dispatchers.IO) { syncSources() }
            logDiagnostics(trigger, startedAt, summary)
            summary
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logDiagnostics(trigger, startedAt, null, e.message)
            throw e
        }
    }

    private fun logDiagnostics(
        trigger: TrackerSyncTrigger,
        startedAt: Long,
        summary: TrackerSyncSummary?,
        error: String? = null
    ) {
        val sources = summary?.sources.orEmpty()
        fun source(name: String): TrackerSyncDiagnosticSource {
            val result = sources.firstOrNull { it.name == name }
                ?: return TrackerSyncDiagnosticSource("not_configured")
            val outcome = when {
                result.errors > 0 && result.updated == 0 -> "failed"
                result.errors > 0 || result.skipped > 0 -> "partial"
                else -> "completed"
            }
            return TrackerSyncDiagnosticSource(outcome, result.updated, result.skipped, result.errors, result.details)
        }
        val moon = sources.firstOrNull { it.name == "Moon+ Reader" }
        TrackerSyncDiagnosticLog.append(
            context,
            TrackerSyncDiagnosticEntry(
                timestamp = System.currentTimeMillis(),
                trigger = trigger.value,
                durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                moonReader = source("Moon+ Reader"),
                moonFilesScanned = moon?.filesScanned ?: 0,
                moonFilesSkipped = moon?.filesSkipped ?: 0,
                moonFilesMatched = moon?.filesMatched ?: 0,
                mangaBaka = source("MangaBaka"),
                aniList = source("AniList"),
                unmatchedCount = moon?.unmatchedItems?.size ?: 0,
                reviewCount = moon?.reviewItems?.size ?: 0,
                unmatchedItems = moon?.unmatchedItems.orEmpty(),
                reviewItems = moon?.reviewItems.orEmpty(),
                errors = listOfNotNull(error)
            )
        )
    }

    private suspend fun syncSources(): TrackerSyncSummary {
        suspend fun attempt(name: String, action: suspend () -> TrackerSyncSourceResult?): TrackerSyncSourceResult? {
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("$name sync failed", e)
                return TrackerSyncSourceResult(
                    name, 0, 0, 1, listOf(e.message ?: "sync failed")
                )
            }
        }

        val preferences = OmniTrackPreferences(context)
        val sourceSemaphore = Semaphore(3)
        val results = coroutineScope {
            listOf(
                async {
                    sourceSemaphore.withPermit {
                        attempt("Moon+ Reader") {
                            if (preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolder) == null ||
                                preferences.moonReaderLinkedEntryIds().isEmpty()
                            ) return@attempt null
                            val result = MoonReaderSync(context, repository).sync(manual = false) {
                                AppLogger.info("Moon+ Reader sync progress")
                            }
                            TrackerSyncSourceResult(
                                "Moon+ Reader", result.updated, result.skipped, result.errors, result.details,
                                result.filesScanned, result.filesSkipped, result.filesMatched,
                                result.unmatchedItems, result.reviewItems
                            )
                        }
                    }
                },
                async {
                    sourceSemaphore.withPermit {
                        attempt("MangaBaka") {
                            val credentials = MangaBakaPreferences(context)
                            val saved = credentials.read()
                            if (saved.accessToken.isBlank() || saved.userId.isBlank() ||
                                credentials.links(saved.userId).isEmpty()
                            ) return@attempt null
                            val service = MangaBakaService(credentials)
                            try {
                                val result = MangaBakaProgressSync(credentials, service, repository)
                                    .syncMangaBakaProgress()
                                TrackerSyncSourceResult(
                                    "MangaBaka", result.updated, result.skipped.size, result.errors.size,
                                    result.skipped + result.errors
                                )
                            } finally {
                                service.close()
                            }
                        }
                    }
                },
                async {
                    sourceSemaphore.withPermit {
                        attempt("AniList") {
                            val credentials = AniListPreferences(context)
                            val saved = credentials.read()
                            if (preferences.getInt(OmniTrackPreferenceKeys.AniListAutoSync, 1) == 0 ||
                                saved.accessToken.isBlank() || saved.userId <= 0 ||
                                preferences.aniListLinks(saved.userId).isEmpty()
                            ) return@attempt null
                            val service = AniListService(credentials)
                            try {
                                val result = AniListProgressSync(credentials, preferences, service, repository).sync()
                                TrackerSyncSourceResult(
                                    "AniList", result.updated, result.skipped.size, result.errors.size,
                                    result.skipped + result.errors
                                )
                            } finally {
                                service.close()
                            }
                        }
                    }
                }
            ).awaitAll().filterNotNull()
        }
        return TrackerSyncSummary(results)
    }

    companion object {
        private val syncFlight = SyncSingleFlight<TrackerSyncSummary>()
    }
}
