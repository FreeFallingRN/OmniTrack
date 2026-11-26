package com.omnitrack.app.data.repository

import android.content.Context
import android.util.Log
import com.omnitrack.app.data.local.ScrapeIssueReport
import com.omnitrack.app.data.local.ScrapeIssueReportDao
import com.omnitrack.app.utils.ImportedMediaDraft
import com.omnitrack.app.worker.ScrapeIssueSyncWorker
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class ScrapeIssueRepository(context: Context, private val dao: ScrapeIssueReportDao) {
    private val appContext = context.applicationContext
    private val json = Json { encodeDefaults = true }
    val reports = dao.observeReports()

    suspend fun save(imported: ImportedMediaDraft, tags: Set<String>, note: String): Boolean {
        val report = ScrapeIssueReport(
            id = UUID.randomUUID().toString(),
            sourceUrl = imported.sourceUrl,
            normalizedUrl = imported.normalizedUrl,
            adapterName = imported.adapterName,
            scrapedSnapshotJson = json.encodeToString(imported.draft),
            issueTagsJson = json.encodeToString(tags.toList()),
            note = note.trim(),
            createdAt = Instant.now().toString()
        )
        dao.insert(report)
        return try {
            ScrapeIssueSyncWorker.enqueue(appContext, report.id)
            true
        } catch (e: Exception) {
            Log.e("ScrapeIssueReport", "Failed to schedule report sync", e)
            false
        }
    }

    suspend fun delete(id: String): Boolean {
        dao.markDeleted(id)
        return try {
            ScrapeIssueSyncWorker.enqueue(appContext, id)
            true
        } catch (e: Exception) {
            Log.e("ScrapeIssueReport", "Failed to schedule report deletion", e)
            false
        }
    }

    suspend fun enqueuePending() {
        dao.pendingIds().forEach { ScrapeIssueSyncWorker.enqueue(appContext, it) }
    }
}
