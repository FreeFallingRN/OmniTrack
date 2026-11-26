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
import com.omnitrack.app.data.MangaBakaProgressSync
import com.omnitrack.app.data.MangaBakaService
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.repository.SeriesRepositoryImpl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class MangaBakaSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = MangaBakaPreferences(applicationContext)
        val service = MangaBakaService(preferences)
        return try {
            val credentials = preferences.read()
            if (credentials.accessToken.isBlank() || credentials.userId.isBlank() ||
                preferences.links(credentials.userId).isEmpty()
            ) {
                Result.failure(workDataOf("skipped" to 1, "details" to "MangaBaka account or links are missing"))
            } else {
                val database = AppDatabase.getInstance(applicationContext)
                val repository = SeriesRepositoryImpl(database, database.mediaEntryDao(), database.progressHistoryDao(), preferences)
                val outcome = MangaBakaProgressSync(preferences, service, repository).syncMangaBakaProgress()
                val data = workDataOf(
                    "updated" to outcome.updated,
                    "skipped" to outcome.skipped.size,
                    "errors" to outcome.errors.size,
                    "details" to (outcome.skipped + outcome.errors).joinToString("; ").take(2000)
                )
                if (outcome.skipped.isNotEmpty() || outcome.errors.isNotEmpty()) {
                    Log.w("MangaBakaSync", "Partial sync: ${data.getString("details")}")
                    Result.failure(data)
                } else Result.success(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Log.e("MangaBakaSync", "Invalid MangaBaka sync state", e)
            Result.failure(workDataOf("errors" to 1, "details" to (e.message ?: "Invalid sync state").take(2000)))
        } catch (e: Exception) {
            Log.e("MangaBakaSync", "Background sync failed", e)
            Result.retry()
        } finally {
            service.close()
        }
    }

    companion object {
        private const val WORK_NAME = "mangabaka_periodic_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MangaBakaSyncWorker>(15, TimeUnit.MINUTES)
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
