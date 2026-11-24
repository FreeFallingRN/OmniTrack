package com.omnitrack.app.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.local.AppDatabase
import kotlinx.coroutines.CancellationException

class ScrapeIssueSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("report_id") ?: return Result.failure()
        val dao = AppDatabase.getInstance(applicationContext).scrapeIssueReportDao()
        return try {
            val report = dao.getById(id) ?: return Result.success()
            if (report.deleted) {
                SupabaseService.deleteScrapeIssueReport(id)
                dao.removeDeleted(id)
            } else if (!report.synced) {
                SupabaseService.uploadScrapeIssueReport(report)
                dao.markSynced(id)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ScrapeIssueSync", "Failed to sync scrape issue report $id", e)
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<ScrapeIssueSyncWorker>()
                .setInputData(workDataOf("report_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scrape_issue_$id",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
