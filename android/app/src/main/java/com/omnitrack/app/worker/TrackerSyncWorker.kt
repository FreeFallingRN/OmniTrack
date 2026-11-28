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
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.TrackerSyncCoordinator
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class TrackerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = run(applicationContext)

    companion object {
        private const val WORK_NAME = "tracker_periodic_sync"
        private val LEGACY_WORK_NAMES = listOf(
            "moon_reader_periodic_sync",
            "mangabaka_periodic_sync",
            "anilist_periodic_sync"
        )

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            LEGACY_WORK_NAMES.forEach { manager.cancelUniqueWork(it) }
            val request = PeriodicWorkRequestBuilder<TrackerSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        internal suspend fun run(context: Context): Result {
            val appContext = context.applicationContext
            return try {
                val database = AppDatabase.getInstance(appContext)
                val repository = SeriesRepositoryImpl(
                    database, database.mediaEntryDao(), database.progressHistoryDao(),
                    MangaBakaPreferences(appContext), OmniTrackPreferences(appContext),
                    AniListPreferences(appContext)
                )
                val summary = TrackerSyncCoordinator(appContext, repository).sync()
                val data = workDataOf(
                    "updated" to summary.updated,
                    "skipped" to summary.skipped,
                    "errors" to summary.errors,
                    "details" to summary.toToastMessage().take(2000)
                )
                if (summary.hasIssues) Log.w("TrackerSync", summary.toToastMessage())
                if (summary.hasErrors) Result.retry() else Result.success(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TrackerSync", "Periodic sync failed", e)
                Result.retry()
            }
        }
    }
}
