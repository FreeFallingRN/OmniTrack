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
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl
import com.omnitrack.app.utils.MoonReaderFolderAccessException
import com.omnitrack.app.utils.MoonReaderSync
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MoonReaderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = AppDatabase.getInstance(applicationContext)
        val repository = SeriesRepositoryImpl(
            database.mediaEntryDao(), database.progressHistoryDao(), MangaBakaPreferences(applicationContext)
        )
        try {
            MoonReaderSync(applicationContext, repository).sync(manual = false) {
                Log.i("MoonReaderSync", it)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoonReaderFolderAccessException) {
            Log.w("MoonReaderSync", "Background sync needs folder access", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e("MoonReaderSync", "Background sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "moon_reader_periodic_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MoonReaderSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
