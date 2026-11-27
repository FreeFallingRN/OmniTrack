package com.omnitrack.app.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.omnitrack.app.data.AniListPreferences
import com.omnitrack.app.data.AniListProgressSync
import com.omnitrack.app.data.AniListService
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class AniListSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val credentials = AniListPreferences(applicationContext)
        val preferences = OmniTrackPreferences(applicationContext)
        val service = AniListService(credentials)
        return try {
            if (preferences.getInt(OmniTrackPreferenceKeys.AniListAutoSync, 1) == 0) return Result.success()
            val saved = credentials.read()
            if (saved.accessToken.isBlank() || saved.userId <= 0 || preferences.aniListLinks(saved.userId).isEmpty()) {
                Result.failure(workDataOf("details" to "AniList account or links are missing"))
            } else {
                val database = AppDatabase.getInstance(applicationContext)
                val repository = SeriesRepositoryImpl(
                    database, database.mediaEntryDao(), database.progressHistoryDao(),
                    MangaBakaPreferences(applicationContext), preferences, credentials
                )
                val result = AniListProgressSync(credentials, preferences, service, repository).sync()
                if (result.skipped.isNotEmpty() || result.errors.isNotEmpty()) {
                    val details = (result.skipped + result.errors).joinToString("; ")
                    Log.w("AniListSync", "Partial sync: $details")
                    if (result.errors.isNotEmpty()) Result.retry()
                    else Result.failure(workDataOf("details" to details.take(2000)))
                } else Result.success(workDataOf("updated" to result.updated))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AniListSync", "Background sync failed", e)
            Result.retry()
        } finally {
            service.close()
        }
    }

    companion object {
        private const val WORK_NAME = "anilist_periodic_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AniListSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
