package com.omnitrack.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.omnitrack.app.data.SupabaseService
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val seriesId = inputData.getString("SERIES_ID") ?: return Result.failure()
        val prevUnit = inputData.getDouble("PREV_UNIT", 0.0)
        val newUnit = inputData.getDouble("NEW_UNIT", -1.0)
        val source = inputData.getString("SOURCE") ?: "android_app"

        if (newUnit < 0.0) return Result.failure()

        return try {
            // 1. Update main cumulative unit on series
            SupabaseService.updateSeriesProgress(seriesId, newUnit)

            // 2. Insert progress history log
            SupabaseService.logProgress(
                seriesId = seriesId,
                entryId = null,
                prevUnit = prevUnit,
                newUnit = newUnit,
                prevCumulative = prevUnit,
                newCumulative = newUnit,
                source = source
            )

            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error syncing progress to Supabase for series $seriesId", e)
            Result.retry()
        }
    }

    companion object {
        fun enqueue(
            context: Context,
            seriesId: String,
            prevUnit: Double,
            newUnit: Double,
            source: String = "android_app"
        ) {
            val data = workDataOf(
                "SERIES_ID" to seriesId,
                "PREV_UNIT" to prevUnit,
                "NEW_UNIT" to newUnit,
                "SOURCE" to source
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync_series_$seriesId",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        }
    }
}