package com.omnitrack.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class MoonReaderSync(context: Context, private val repository: SeriesRepository) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE)

    fun hasFolderAccess(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    suspend fun unlink(entryId: String) = mutex.withLock {
        check(prefs.edit()
            .remove(FILE_URI + entryId)
            .remove(FILE_NAME + entryId)
            .remove(OFFSET + entryId)
            .remove(SPINE_OFFSET + entryId)
            .remove(TIMESTAMP + entryId)
            .remove(SPINE + entryId)
            .remove(LAST_MODIFIED + entryId)
            .commit()) { "Could not unlink Moon+ Reader book" }
    }

    suspend fun apply(match: MoonReaderMatch, value: Double, folder: String?) = mutex.withLock {
        val entries = repository.getMediaEntriesStream().first()
        val current = entries.firstOrNull { it.id == match.entry.id }
        check(current != null && current.currentUnit == match.entry.currentUnit &&
            current.unitName == match.entry.unitName) {
            "${match.entry.title} changed since the preview; scan again"
        }
        val progress = match.progress
        val isChapter = isChapterUnit(current.unitName)
        if (folder != null && isChapter && progress.fileUri != null && progress.fileName != null) {
            check(prefs.getString("moon_reader_folder", null) == folder && hasFolderAccess(Uri.parse(folder))) {
                "Folder access changed; scan again"
            }
            val duplicate = entries.any {
                it.id != current.id && prefs.getString(FILE_URI + it.id, null) == progress.fileUri.toString()
            }
            check(!duplicate) { "${progress.fileName} is already linked to another entry" }
        }
        repository.updateProgress(
            current.id, value,
            if (isChapter && folder != null && progress.fileUri != null && progress.fileName != null) {
                ProgressSource.DETAIL_SHEET
            } else ProgressSource.IMPORT
        )
        if (isChapter) {
            val editor = prefs.edit()
                .putLong(OFFSET + current.id, (value - progress.spineChapterIndex - 1.0).toRawBits())
                .putLong(SPINE_OFFSET + current.id, (value - progress.spineChapterIndex).toRawBits())
            if (folder != null && progress.fileUri != null && progress.fileName != null) {
                editor.putString(FILE_URI + current.id, progress.fileUri.toString())
                    .putString(FILE_NAME + current.id, progress.fileName)
                    .putLong(TIMESTAMP + current.id, progress.timestamp)
                    .putInt(SPINE + current.id, progress.spineChapterIndex)
                if (progress.lastModified != null && progress.lastModified > 0) {
                    editor.putLong(LAST_MODIFIED + current.id, progress.lastModified)
                } else {
                    editor.remove(LAST_MODIFIED + current.id)
                }
            }
            check(editor.commit()) { "Could not save Moon+ Reader state for ${current.title}" }
        }
    }

    suspend fun sync(manual: Boolean, notify: suspend (String) -> Unit) = mutex.withLock {
        val folder = prefs.getString("moon_reader_folder", null)
        if (folder == null) {
            if (manual) notify("Moon+ Reader: choose a folder first")
            return@withLock
        }
        val uri = Uri.parse(folder)
        val entries = repository.getMediaEntriesStream().first()
        var skipped = false
        val links = entries.mapNotNull { entry ->
            val fileUri = prefs.getString(FILE_URI + entry.id, null)
            val name = prefs.getString(FILE_NAME + entry.id, null)
            if (fileUri != null && name != null) {
                if (!isChapterUnit(entry.unitName)) {
                    if (manual) notify("Moon+ Reader: ${entry.title} no longer uses chapters")
                    else Log.w("MoonReaderSync", "${entry.title} no longer uses chapters")
                    skipped = true
                    return@mapNotNull null
                }
                entry.id to MoonReaderFile(
                    fileUri, name,
                    prefs.getLong(LAST_MODIFIED + entry.id, -1L),
                    prefs.getLong(TIMESTAMP + entry.id, -1L)
                )
            } else null
        }.toMap()
        if (links.isEmpty()) {
            if (manual && !skipped) notify("Moon+ Reader: no linked chapter entries")
            return@withLock
        }
        if (!hasMoonReaderFolderAccess(context, uri)) throw MoonReaderFolderAccessException(folder)
        val scan = try {
            moonReaderProviderCall(60_000L) {
                MoonReaderImport.scan(context, uri, true, entries, links)
            } ?: throw MoonReaderFolderAccessException(folder)
        } catch (e: SecurityException) {
            throw MoonReaderFolderAccessException(folder)
        }
        var updates = 0
        for (match in scan.matches) {
            if (!hasFolderAccess(uri)) throw MoonReaderFolderAccessException(folder)
            check(prefs.getString("moon_reader_folder", null) == folder) {
                "Folder access changed during sync; try again"
            }
            val entry = match.entry
            if (!isChapterUnit(entry.unitName)) {
                if (manual) notify("Moon+ Reader: ${entry.title} no longer uses chapters")
                else Log.w("MoonReaderSync", "${entry.title} no longer uses chapters")
                skipped = true
                continue
            }
            val progress = match.progress
            val fileUri = requireNotNull(progress.fileUri).toString()
            val lastTime = prefs.getLong(TIMESTAMP + entry.id, -1L)
            val lastSpine = prefs.getInt(SPINE + entry.id, -1)
            if (progress.timestamp < lastTime ||
                (progress.timestamp == lastTime && progress.spineChapterIndex <= lastSpine)) {
                val editor = prefs.edit().putString(FILE_URI + entry.id, fileUri)
                    .putString(FILE_NAME + entry.id, requireNotNull(progress.fileName))
                if (progress.lastModified != null && progress.lastModified > 0) {
                    editor.putLong(LAST_MODIFIED + entry.id, progress.lastModified)
                } else editor.remove(LAST_MODIFIED + entry.id)
                check(editor.commit()) { "Could not save Moon+ Reader file reference for ${entry.title}" }
                continue
            }
            check(prefs.contains(SPINE_OFFSET + entry.id)) { "Missing chapter offset for ${entry.title}" }
            val offset = Double.fromBits(prefs.getLong(SPINE_OFFSET + entry.id, 0L))
            check(offset.isFinite()) { "Invalid saved chapter offset for ${entry.title}" }
            val chapter = offset + progress.spineChapterIndex
            check(chapter.isFinite() && chapter >= 0.0) { "Invalid chapter for ${entry.title}" }
            val updated = repository.updateMoonReaderChapterIfHigher(entry.id, chapter)
            val editor = prefs.edit()
                .putString(FILE_URI + entry.id, fileUri)
                .putString(FILE_NAME + entry.id, requireNotNull(progress.fileName))
                .putLong(TIMESTAMP + entry.id, progress.timestamp)
                .putInt(SPINE + entry.id, progress.spineChapterIndex)
            if (progress.lastModified != null && progress.lastModified > 0) {
                editor.putLong(LAST_MODIFIED + entry.id, progress.lastModified)
            } else editor.remove(LAST_MODIFIED + entry.id)
            check(editor.commit()) { "Could not save Moon+ Reader sync state for ${entry.title}" }
            if (updated) {
                val formatted = if (chapter % 1.0 == 0.0) chapter.toLong().toString() else chapter.toString()
                notify("Moon+ Reader: updated ${entry.title} to Ch. $formatted")
                updates++
            }
        }
        if (scan.hasProblems) {
            if (manual) notify("Moon+ Reader: ${scan.message}")
            else Log.w("MoonReaderSync", scan.message)
        }
        if (manual && updates == 0 && !skipped && !scan.hasProblems) notify("Moon+ Reader: already up to date")
    }

    companion object {
        private val mutex = Mutex()
        private const val OFFSET = "moon_reader_chapter_offset_"
        private const val SPINE_OFFSET = "moon_reader_spine_offset_"
        private const val FILE_URI = "moon_reader_file_uri_"
        private const val FILE_NAME = "moon_reader_file_name_"
        private const val TIMESTAMP = "moon_reader_timestamp_"
        private const val SPINE = "moon_reader_spine_"
        private const val LAST_MODIFIED = "moon_reader_last_modified_"

        fun isChapterUnit(unit: String): Boolean =
            unit.trim().equals("chapter", ignoreCase = true) ||
                unit.trim().equals("chapters", ignoreCase = true)
    }
}
