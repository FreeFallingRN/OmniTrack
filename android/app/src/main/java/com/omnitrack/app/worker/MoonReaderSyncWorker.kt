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
            database, database.mediaEntryDao(), database.progressHistoryDao(), MangaBakaPreferences(applicationContext)
        )
        try {
            val outcome = MoonReaderSync(applicationContext, repository).sync(manual = false) {
                Log.i("MoonReaderSync", it)
            }
            val data = workDataOf(
                "updated" to outcome.updated, "skipped" to outcome.skipped, "errors" to outcome.errors,
                "details" to outcome.details.joinToString("; ").take(2000)
            )
            if (outcome.skipped > 0 || outcome.errors > 0) Result.failure(data) else Result.success(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoonReaderFolderAccessException) {
            Log.w("MoonReaderSync", "Background sync needs folder access", e)
            Result.failure(workDataOf("errors" to 1, "details" to (e.message ?: "Folder access expired")))
        } catch (e: IllegalArgumentException) {
            Log.e("MoonReaderSync", "Invalid Moon+ Reader sync state", e)
            Result.failure(workDataOf("errors" to 1, "details" to (e.message ?: "Invalid sync state").take(2000)))
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
