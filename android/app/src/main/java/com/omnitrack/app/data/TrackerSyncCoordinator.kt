package com.omnitrack.app.data

import android.content.Context
import android.util.Log
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.utils.MoonReaderSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrackerSyncSourceResult(
    val name: String,
    val updated: Int,
    val skipped: Int,
    val errors: Int,
    val details: List<String>
)

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

    suspend fun sync(): TrackerSyncSummary = syncFlight.run {
        withContext(Dispatchers.IO) { syncSources() }
    }

    private suspend fun syncSources(): TrackerSyncSummary {
        val results = mutableListOf<TrackerSyncSourceResult>()
        suspend fun attempt(name: String, action: suspend () -> TrackerSyncSourceResult?) {
            try {
                action()?.let(results::add)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TrackerSync", "$name sync failed", e)
                results.add(TrackerSyncSourceResult(
                    name, 0, 0, 1, listOf(e.message ?: "sync failed")
                ))
            }
        }

        val preferences = OmniTrackPreferences(context)
        attempt("Moon+ Reader") {
            if (preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolder) == null ||
                preferences.moonReaderLinkedEntryIds().isEmpty()
            ) return@attempt null
            val result = MoonReaderSync(context, repository).sync(manual = false) {
                Log.i("MoonReaderSync", it)
            }
            TrackerSyncSourceResult(
                "Moon+ Reader", result.updated, result.skipped, result.errors, result.details
            )
        }
        attempt("MangaBaka") {
            val credentials = MangaBakaPreferences(context)
            val saved = credentials.read()
            if (saved.accessToken.isBlank() || saved.userId.isBlank() ||
                credentials.links(saved.userId).isEmpty()
            ) return@attempt null
            val service = MangaBakaService(credentials)
            try {
                val result = MangaBakaProgressSync(credentials, service, repository).syncMangaBakaProgress()
                TrackerSyncSourceResult(
                    "MangaBaka", result.updated, result.skipped.size, result.errors.size,
                    result.skipped + result.errors
                )
            } finally {
                service.close()
            }
        }
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
        return TrackerSyncSummary(results)
    }

    companion object {
        private val syncFlight = SyncSingleFlight<TrackerSyncSummary>()
    }
}
