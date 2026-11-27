package com.omnitrack.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omnitrack.app.data.AniListPreferences
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.TrackerStatusSync
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl

class TrackerStatusSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.getInstance(applicationContext)
        val repository = SeriesRepositoryImpl(
            database, database.mediaEntryDao(), database.progressHistoryDao(),
            MangaBakaPreferences(applicationContext), OmniTrackPreferences(applicationContext),
            AniListPreferences(applicationContext)
        )
        return if (TrackerStatusSync(applicationContext, repository).sync().isEmpty()) Result.success()
        else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackerStatusSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
