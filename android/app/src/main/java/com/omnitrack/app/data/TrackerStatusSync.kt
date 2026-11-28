package com.omnitrack.app.data

import android.content.Context
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException

class TrackerStatusSync(context: Context, private val repository: SeriesRepository) {
    private val aniPreferences = AniListPreferences(context)
    private val preferences = OmniTrackPreferences(context)
    private val mangaPreferences = MangaBakaPreferences(context)
    private val aniService = AniListService(aniPreferences)
    private val mangaService = MangaBakaService(mangaPreferences)

    suspend fun sync(): List<String> {
        val errors = mutableListOf<String>()
        suspend fun attempt(name: String, action: suspend () -> Unit) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("$name status sync failed", e)
                errors.add("$name: ${e.message ?: "sync failed"}")
            }
        }
        try {
            attempt("AniList") {
                AniListProgressSync(aniPreferences, preferences, aniService, repository).pushPendingStatuses()
            }
            attempt("MangaBaka") {
                MangaBakaProgressSync(mangaPreferences, mangaService, repository).pushPendingStatuses()
            }
            attempt("Supabase") { repository.retryPendingLibraryMutations() }
        } finally {
            aniService.close()
            mangaService.close()
        }
        return errors
    }
}
