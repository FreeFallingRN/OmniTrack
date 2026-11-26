package com.omnitrack.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.omnitrack.app.data.MangaBakaPreferences
import com.omnitrack.app.data.MangaBakaProgressSync
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal suspend fun <T> moonReaderProviderCall(timeout: Long, block: suspend () -> T): T? =
    withTimeoutOrNull(timeout) {
        suspendCancellableCoroutine { continuation ->
            val job = providerScope.launch {
                try {
                    continuation.resume(block())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation { job.cancel() }
        }
    }

internal suspend fun hasMoonReaderFolderAccess(context: Context, uri: Uri): Boolean =
    moonReaderProviderCall(10_000L) {
        runCatching {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } &&
                DocumentFile.fromTreeUri(context, uri)?.canRead() == true
        }.onFailure { Log.w("MoonReaderSync", "Could not check folder access", it) }.getOrDefault(false)
    } == true

internal const val MOON_READER_FOLDER_EXPIRED = "Folder access expired \u2014 tap to re-grant access"
internal class MoonReaderFolderAccessException(val folder: String) : IllegalStateException(MOON_READER_FOLDER_EXPIRED)

internal class MoonReaderProviderTimeoutException : IllegalStateException("Moon+ Reader provider timed out")

data class MoonReaderSyncResult(val updated: Int, val skipped: Int, val errors: Int, val details: List<String>)

class MoonReaderSync(context: Context, private val repository: SeriesRepository) {
    private val context = context.applicationContext
    private val prefs = OmniTrackPreferences(this.context)
    private val mangaBakaPreferences = MangaBakaPreferences(this.context)

    fun hasFolderAccess(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    suspend fun unlink(entryId: String) = MangaBakaProgressSync.withLinkLock {
        prefs.unlinkMoonReader(entryId)
    }

    suspend fun apply(match: MoonReaderMatch, value: Double, folder: String?) = MangaBakaProgressSync.withLinkLock {
        val entries = repository.getMediaEntriesStream().first()
        val current = entries.firstOrNull { it.id == match.entry.id }
        check(current != null && current.currentUnit == match.entry.currentUnit &&
            current.unitName == match.entry.unitName && current.type == match.entry.type) {
            "${match.entry.title} changed since the preview; scan again"
        }
        check(value.isFinite() && value in 0.0..99999.99 &&
            java.math.BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 2) {
            "Invalid chapter for ${current.title}"
        }
        val progress = match.progress
        val isChapter = isChapterUnit(current.unitName)
        if (folder != null && isChapter && progress.fileUri != null && progress.fileName != null) {
            check(prefs.getString(OmniTrackPreferenceKeys.MoonReaderFolder) == folder &&
                hasFolderAccess(Uri.parse(folder))) {
                "Folder access changed; scan again"
            }
            val duplicate = entries.any {
                it.id != current.id &&
                    prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.id)) == progress.fileUri.toString()
            }
            check(!duplicate) { "${progress.fileName} is already linked to another entry" }
        }
        repository.updateMoonReaderProgress(
            current.id, value,
            if (isChapter && folder != null && progress.fileUri != null && progress.fileName != null) {
                ProgressSource.DETAIL_SHEET
            } else ProgressSource.IMPORT
        )
        if (isChapter) {
            val editor = prefs.edit()
                .putLong(
                    OmniTrackPreferenceKeys.moonReaderChapterOffset(current.id),
                    BigDecimal.valueOf(value).subtract(BigDecimal.valueOf(progress.spineChapterIndex.toLong() + 1))
                        .toDouble().toRawBits()
                )
                .putLong(
                    OmniTrackPreferenceKeys.moonReaderSpineOffset(current.id),
                    BigDecimal.valueOf(value).subtract(BigDecimal.valueOf(progress.spineChapterIndex.toLong()))
                        .toDouble().toRawBits()
                )
            if (folder != null && progress.fileUri != null && progress.fileName != null) {
                editor.putString(OmniTrackPreferenceKeys.moonReaderFileUri(current.id), progress.fileUri.toString())
                    .putString(OmniTrackPreferenceKeys.moonReaderFileName(current.id), progress.fileName)
                    .putLong(OmniTrackPreferenceKeys.moonReaderTimestamp(current.id), progress.timestamp)
                    .putInt(OmniTrackPreferenceKeys.moonReaderSpine(current.id), progress.spineChapterIndex)
                if (progress.lastModified != null && progress.lastModified > 0) {
                    editor.putLong(OmniTrackPreferenceKeys.moonReaderLastModified(current.id), progress.lastModified)
                } else {
                    editor.remove(OmniTrackPreferenceKeys.moonReaderLastModified(current.id))
                }
            }
            check(editor.commit()) { "Could not save Moon+ Reader state for ${current.title}" }
        }
    }

    suspend fun sync(manual: Boolean, notify: suspend (String) -> Unit) = MangaBakaProgressSync.withLinkLock {
        val folder = prefs.getString(OmniTrackPreferenceKeys.MoonReaderFolder)
        if (folder == null) {
            if (manual) notify("Moon+ Reader: choose a folder first")
            return@withLinkLock MoonReaderSyncResult(0, 1, 0, listOf("Choose a folder first"))
        }
        val uri = Uri.parse(folder)
        val entries = repository.getMediaEntriesStream().first()
        val skippedDetails = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val entryIds = entries.map { it.id }.toSet()
        (prefs.moonReaderLinkedEntryIds() - entryIds).forEach {
            skippedDetails.add("Linked entry $it no longer exists")
        }
        val links = entries.mapNotNull { entry ->
            val fileUri = prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(entry.id))
            val name = prefs.getString(OmniTrackPreferenceKeys.moonReaderFileName(entry.id))
            if (fileUri != null && name != null) {
                val issue = when {
                    !isChapterUnit(entry.unitName) -> "${entry.title} no longer uses chapters"
                    entry.type != MediaType.NOVEL && entry.type != MediaType.COMIC ->
                        "${entry.title} is not a novel or comic"
                    entry.type == MediaType.COMIC && mangaBakaPreferences.isMangaBakaLinked(entry.id) ->
                        "${entry.title} is controlled by MangaBaka"
                    !prefs.contains(OmniTrackPreferenceKeys.moonReaderSpineOffset(entry.id)) ->
                        "Missing chapter offset for ${entry.title}"
                    !Double.fromBits(prefs.getLong(OmniTrackPreferenceKeys.moonReaderSpineOffset(entry.id))).isFinite() ->
                        "Invalid chapter offset for ${entry.title}"
                    else -> null
                }
                if (issue != null) {
                    skippedDetails.add(issue)
                    return@mapNotNull null
                }
                entry.id to MoonReaderFile(
                    fileUri, name,
                    prefs.getLong(OmniTrackPreferenceKeys.moonReaderLastModified(entry.id), -1L),
                    prefs.getLong(OmniTrackPreferenceKeys.moonReaderTimestamp(entry.id), -1L)
                )
            } else {
                if (fileUri != null || name != null) skippedDetails.add("${entry.title}: incomplete book link")
                null
            }
        }.toMap()
        if (links.isEmpty()) {
            if (skippedDetails.isEmpty()) skippedDetails.add("No linked chapter entries")
            if (manual) notify("Moon+ Reader: ${skippedDetails.joinToString("; ")}")
            return@withLinkLock MoonReaderSyncResult(0, skippedDetails.size, 0, skippedDetails)
        }
        if (!hasFolderAccess(uri)) throw MoonReaderFolderAccessException(folder)
        val scan = try {
            val readable = moonReaderProviderCall(10_000L) {
                DocumentFile.fromTreeUri(context, uri)?.canRead() == true
            } ?: throw MoonReaderProviderTimeoutException()
            if (!readable) throw MoonReaderFolderAccessException(folder)
            moonReaderProviderCall(60_000L) {
                MoonReaderImport.scan(context, uri, true, entries, links)
            } ?: throw MoonReaderProviderTimeoutException()
        } catch (e: SecurityException) {
            throw MoonReaderFolderAccessException(folder)
        }
        var updates = 0
        for (match in scan.matches) {
          try {
            if (!hasFolderAccess(uri)) throw MoonReaderFolderAccessException(folder)
            check(prefs.getString(OmniTrackPreferenceKeys.MoonReaderFolder) == folder) {
                "Folder access changed during sync; try again"
            }
            val entry = match.entry
            if (!isChapterUnit(entry.unitName)) {
                skippedDetails.add("${entry.title} no longer uses chapters")
                continue
            }
            val progress = match.progress
            val fileUri = requireNotNull(progress.fileUri).toString()
            val lastTime = prefs.getLong(OmniTrackPreferenceKeys.moonReaderTimestamp(entry.id), -1L)
            val lastSpine = prefs.getInt(OmniTrackPreferenceKeys.moonReaderSpine(entry.id), -1)
            if (progress.timestamp < lastTime ||
                (progress.timestamp == lastTime && progress.spineChapterIndex <= lastSpine)) {
                val editor = prefs.edit()
                    .putString(OmniTrackPreferenceKeys.moonReaderFileUri(entry.id), fileUri)
                    .putString(OmniTrackPreferenceKeys.moonReaderFileName(entry.id), requireNotNull(progress.fileName))
                if (progress.lastModified != null && progress.lastModified > 0) {
                    editor.putLong(OmniTrackPreferenceKeys.moonReaderLastModified(entry.id), progress.lastModified)
                } else editor.remove(OmniTrackPreferenceKeys.moonReaderLastModified(entry.id))
                check(editor.commit()) { "Could not save Moon+ Reader file reference for ${entry.title}" }
                continue
            }
            val spineOffset = OmniTrackPreferenceKeys.moonReaderSpineOffset(entry.id)
            if (!prefs.contains(spineOffset)) {
                skippedDetails.add("Missing chapter offset for ${entry.title}")
                continue
            }
            val offset = Double.fromBits(prefs.getLong(spineOffset))
            if (!offset.isFinite()) {
                skippedDetails.add("Invalid chapter or offset for ${entry.title}")
                continue
            }
            val chapterValue = BigDecimal.valueOf(offset).add(BigDecimal.valueOf(progress.spineChapterIndex.toLong()))
            if (chapterValue < BigDecimal.ZERO || chapterValue > BigDecimal("99999.99") ||
                chapterValue.stripTrailingZeros().scale() > 2) {
                skippedDetails.add("Invalid chapter or offset for ${entry.title}")
                continue
            }
            val chapter = chapterValue.toDouble()
            val updated = repository.updateMoonReaderChapterIfHigher(entry.id, chapter)
            if (updated) updates++
            val editor = prefs.edit()
                .putString(OmniTrackPreferenceKeys.moonReaderFileUri(entry.id), fileUri)
                .putString(OmniTrackPreferenceKeys.moonReaderFileName(entry.id), requireNotNull(progress.fileName))
                .putLong(OmniTrackPreferenceKeys.moonReaderTimestamp(entry.id), progress.timestamp)
                .putInt(OmniTrackPreferenceKeys.moonReaderSpine(entry.id), progress.spineChapterIndex)
            if (progress.lastModified != null && progress.lastModified > 0) {
                editor.putLong(OmniTrackPreferenceKeys.moonReaderLastModified(entry.id), progress.lastModified)
            } else editor.remove(OmniTrackPreferenceKeys.moonReaderLastModified(entry.id))
            check(editor.commit()) { "Could not save Moon+ Reader sync state for ${entry.title}" }
            if (updated) {
                val formatted = if (chapter % 1.0 == 0.0) chapter.toLong().toString() else chapter.toString()
                notify("Moon+ Reader: updated ${entry.title} to Ch. $formatted")
            }
          } catch (e: CancellationException) {
              throw e
          } catch (e: MoonReaderFolderAccessException) {
              throw e
          } catch (e: Exception) {
              Log.e("MoonReaderSync", "Could not sync ${match.entry.title}", e)
              errors.add("${match.entry.title}: ${e.message ?: "sync failed"}")
          }
        }
        if (scan.hasProblems) skippedDetails.add(scan.message)
        val skipped = skippedDetails.size - (if (scan.hasProblems) 1 else 0) + scan.skipped
        val errorCount = errors.size + scan.errors
        val details = skippedDetails + errors
        if (skipped > 0 || errorCount > 0) {
            val message = "Moon+ Reader: $updates updated, $skipped skipped, $errorCount errors. ${details.joinToString("; ")}"
            if (manual) notify(message) else Log.w("MoonReaderSync", message)
        }
        if (manual && updates == 0 && skipped == 0 && errorCount == 0) notify("Moon+ Reader: already up to date")
        MoonReaderSyncResult(updates, skipped, errorCount, details)
    }

    companion object {
        fun isChapterUnit(unit: String): Boolean =
            unit.trim().equals("chapter", ignoreCase = true) ||
                unit.trim().equals("chapters", ignoreCase = true)
    }
}
