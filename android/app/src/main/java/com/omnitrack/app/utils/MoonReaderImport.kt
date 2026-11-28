package com.omnitrack.app.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.omnitrack.app.data.AppLogger
import com.omnitrack.app.domain.model.MediaEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class MoonReaderProgress(
    val title: String,
    val spineChapterIndex: Int,
    val percent: Double,
    val timestamp: Long,
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val lastModified: Long? = null,
    val fileSize: Long? = null
)
data class MoonReaderMatch(val entry: MediaEntry, val progress: MoonReaderProgress)
data class MoonReaderAmbiguous(val progress: MoonReaderProgress, val candidates: List<MediaEntry>)
data class MoonReaderScan(
    val matches: List<MoonReaderMatch>,
    val message: String,
    val skipped: Int,
    val errors: Int,
    val ambiguous: List<MoonReaderAmbiguous> = emptyList(),
    val unmatched: List<MoonReaderProgress> = emptyList(),
    val filesScanned: Int = 0,
    val filesSkipped: Int = 0,
    val unmatchedCount: Int = 0,
    val reviewCount: Int = 0,
    val unmatchedDetails: List<String> = emptyList(),
    val reviewDetails: List<String> = emptyList()
) {
    val hasProblems: Boolean get() = skipped > 0 || errors > 0
}
data class MoonReaderFile(
    val uri: String, val name: String, val lastModified: Long, val timestamp: Long, val fileSize: Long
)

object MoonReaderImport {
    private const val MAX_FILES = 4000
    private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_ITEM_BYTES = 64 * 1024
    private val position = Regex("""^(\d+)\*(\d+)@\d+#\d+:([0-9]+(?:\.[0-9]+)?)%""")
    private val bookExtension = Regex("""\.(epub|pdf|mobi|azw3?|fb2|txt|cbz|cbr)$""", RegexOption.IGNORE_CASE)
    private data class FileInfo(val uri: Uri, val name: String, val lastModified: Long?, val fileSize: Long?)

    suspend fun scan(
        context: Context,
        uri: Uri,
        tree: Boolean,
        entries: List<MediaEntry>,
        linkedFiles: Map<String, MoonReaderFile>? = null
    ): MoonReaderScan =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val files = mutableListOf<FileInfo>()
            var problems = 0
            val linkedNames = linkedFiles?.values?.map { it.name }?.toSet()
            val linkedUris = linkedFiles?.values?.map { it.uri }?.toSet()

            fun addFile(file: Uri, name: String, lastModified: Long?, fileSize: Long?) {
                if (linkedFiles != null) {
                    if (!name.endsWith(".po", true) ||
                        (name !in linkedNames.orEmpty() && file.toString() !in linkedUris.orEmpty())) return
                } else if (!name.endsWith(".po", true) &&
                    !name.endsWith(".mrpro", true) && !name.endsWith(".mrstd", true) &&
                    !name.endsWith(".zip", true)) return
                files.add(FileInfo(file, name, lastModified, fileSize))
            }

            if (tree) {
                val root = DocumentsContract.getTreeDocumentId(uri)
                val pending = ArrayDeque<Pair<String, Int>>()
                val visited = mutableSetOf<String>()
                pending.add(root to 0)
                var count = 0
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (id, depth) = pending.removeFirst()
                    if (!visited.add(id)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                    val cursor = resolver.query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            DocumentsContract.Document.COLUMN_SIZE
                        ),
                        null, null, null
                    ) ?: error("Unable to list the selected folder")
                    cursor.use {
                        while (it.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            count++
                            check(count <= MAX_FILES) { "Folder contains more than $MAX_FILES items" }
                            val childId = it.getString(0) ?: continue
                            val name = it.getString(1) ?: continue
                            if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < 8) pending.add(childId to depth + 1) else problems++
                            } else {
                                addFile(
                                    DocumentsContract.buildDocumentUriUsingTree(uri, childId), name,
                                    if (it.isNull(3)) null else it.getLong(3),
                                    if (it.isNull(4)) null else it.getLong(4)
                                )
                            }
                        }
                    }
                }
            } else {
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                addFile(uri, name, null, null)
            }

            var ambiguous = 0
            var unmatched = 0
            val mappingProblems = mutableListOf<String>()
            val unmatchedDetails = mutableListOf<String>()
            val reviewDetails = mutableListOf<String>()
            val filesByUri = files.groupBy { it.uri.toString() }
            val filesByName = files.groupBy { it.name }
            val linkedCandidates = linkedFiles?.let { links ->
                val linkNameCounts = links.values.groupingBy { it.name }.eachCount()
                val claimedUris = links.values.mapTo(mutableSetOf()) { it.uri }
                entries.mapNotNull { entry ->
                    val link = links[entry.id] ?: return@mapNotNull null
                    val exact = filesByUri[link.uri].orEmpty()
                    val named = if (linkNameCounts[link.name] == 1) {
                        filesByName[link.name].orEmpty().filter { it.uri.toString() !in claimedUris }
                    } else emptyList()
                    val matching = if (exact.isNotEmpty()) exact else named
                    when (matching.size) {
                        0 -> {
                            unmatched++
                            val detail = "${entry.title}: linked file not found"
                            mappingProblems.add(detail)
                            unmatchedDetails.add(detail)
                            null
                        }
                        1 -> entry.id to matching.single()
                        else -> {
                            ambiguous++
                            val detail = "${entry.title}: multiple matching files"
                            mappingProblems.add(detail)
                            reviewDetails.add(detail)
                            null
                        }
                    }
                }.toMap()
            }
            val filesToRead = if (linkedFiles != null && linkedCandidates != null) {
                linkedCandidates.filter { (id, file) ->
                    val link = linkedFiles.getValue(id)
                    file.uri.toString() != link.uri || file.name != link.name || file.lastModified == null ||
                        file.lastModified <= 0 || file.lastModified != link.lastModified ||
                        (file.fileSize ?: -1L) != link.fileSize || link.timestamp < 0
                }.values.distinctBy { it.uri }
            } else files
            val semaphore = Semaphore(5)
            val results = coroutineScope {
                filesToRead.map { file ->
                    async {
                        semaphore.withPermit {
                            try {
                                val stream = resolver.openInputStream(file.uri)
                                    ?: error("Unable to open ${file.name}")
                                stream.use {
                                    if (file.name.endsWith(".po", true)) {
                                        listOfNotNull(parsePosition(file.name, readLimited(it, MAX_ITEM_BYTES))?.copy(
                                            fileUri = file.uri, fileName = file.name, lastModified = file.lastModified,
                                            fileSize = file.fileSize
                                        ))
                                    } else readArchive(it)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.warn(
                                    "[MoonReaderImport] Unable to read progress file",
                                    e,
                                    secrets = listOf(file.name)
                                )
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()
            }
            problems += results.count { it.isEmpty() }
            val unreadable = filesToRead.zip(results).filter { it.second.isEmpty() }.map { it.first.name }
            val found = results.flatten()
            val ambiguousItems = mutableListOf<MoonReaderAmbiguous>()
            val unmatchedItems = mutableListOf<MoonReaderProgress>()
            val byTitle = entries.flatMap { entry ->
                (listOf(entry.title) + entry.allAlternativeTitles).map { normalize(it) to entry }
            }.filter { it.first.isNotEmpty() }.groupBy({ it.first }, { it.second })
            val candidates = if (linkedFiles != null) {
                entries.mapNotNull { entry ->
                    val file = linkedCandidates?.get(entry.id) ?: return@mapNotNull null
                    found.firstOrNull { it.fileUri == file.uri }?.let { MoonReaderMatch(entry, it) }
                }
            } else found.mapNotNull { item ->
                val matches = byTitle[normalize(item.title)].orEmpty().distinctBy { it.id }
                when (matches.size) {
                    0 -> {
                        unmatched++
                        unmatchedItems.add(item)
                        unmatchedDetails.add(item.title)
                        null
                    }
                    1 -> MoonReaderMatch(matches.single(), item)
                    else -> {
                        ambiguous++
                        ambiguousItems.add(MoonReaderAmbiguous(item, matches))
                        reviewDetails.add(item.title)
                        null
                    }
                }
            }
            val matches = candidates.groupBy { it.entry.id }.values.mapNotNull { group ->
                val directFiles = if (tree) group.filter { it.progress.fileUri != null } else emptyList()
                val preferred = directFiles.ifEmpty { group }
                val latest = preferred.maxOf { it.progress.timestamp }
                val newest = preferred.filter { it.progress.timestamp == latest }
                if (newest.map { it.progress.spineChapterIndex to it.progress.percent }.distinct().size > 1 ||
                    (tree && newest.mapNotNull { it.progress.fileUri }.distinct().size > 1)) {
                    if (linkedFiles == null) {
                        group.forEach {
                            ambiguousItems.add(MoonReaderAmbiguous(it.progress, listOf(it.entry)))
                            reviewDetails.add(it.progress.title)
                        }
                        ambiguous += group.size
                    } else {
                        ambiguous++
                        reviewDetails.add("${group.first().entry.title}: conflicting positions")
                    }
                    null
                } else {
                    val chosen = newest.first()
                    if (linkedFiles == null) {
                        group.filter { it !== chosen }.forEach {
                            ambiguousItems.add(MoonReaderAmbiguous(it.progress, listOf(it.entry)))
                            ambiguous++
                        }
                    }
                    chosen
                }
            }.sortedBy { it.entry.title.lowercase(Locale.ROOT) }
            MoonReaderScan(
                matches,
                "Found ${found.size} positions in ${filesToRead.size} files; $unmatched unmatched, " +
                    "$ambiguous ambiguous, $problems unreadable or unsupported." +
                    (mappingProblems + unreadable.map { "$it: unreadable or unsupported" })
                        .take(10).joinToString(separator = "; ", prefix = " ").trimEnd(),
                unmatched + ambiguous,
                problems,
                ambiguousItems,
                unmatchedItems,
                filesScanned = filesToRead.size,
                filesSkipped = if (linkedFiles != null) {
                    (linkedFiles.size - filesToRead.size).coerceAtLeast(0)
                } else {
                    (files.size - filesToRead.size).coerceAtLeast(0)
                },
                unmatchedCount = unmatched,
                reviewCount = ambiguous,
                unmatchedDetails = unmatchedDetails,
                reviewDetails = reviewDetails
            )
        }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    private fun parsePosition(name: String, data: ByteArray): MoonReaderProgress? {
        val text = data.toString(Charsets.UTF_8).trim()
        val match = position.find(text) ?: return null
        val timestamp = match.groupValues[1].toLongOrNull() ?: return null
        val spineChapterIndex = match.groupValues[2].toIntOrNull() ?: return null
        val percent = match.groupValues[3].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: return null
        val filename = name.substringAfterLast('/').substringAfterLast('\\')
        val title = bookExtension.replace(filename.dropLast(3), "").trim()
        return title.takeIf { it.isNotEmpty() }?.let {
            MoonReaderProgress(it, spineChapterIndex, percent, timestamp, fileName = filename)
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "File is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readArchive(stream: InputStream): List<MoonReaderProgress> {
        val files = mutableMapOf<String, ByteArray>()
        var total = 0
        var count = 0
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++count <= MAX_FILES) { "Archive contains too many files" }
                val keep = !entry.isDirectory && (entry.name.endsWith(".po", true) ||
                    entry.name.endsWith(".tag", true) || entry.name.endsWith("_names.list", true))
                val output = if (keep) ByteArrayOutputStream() else null
                val buffer = ByteArray(4096)
                var itemSize = 0
                while (true) {
                    val bytes = zip.read(buffer)
                    if (bytes < 0) break
                    total += bytes
                    check(total <= MAX_ARCHIVE_BYTES) { "Archive is too large" }
                    itemSize += bytes
                    if (itemSize <= MAX_ITEM_BYTES) output?.write(buffer, 0, bytes)
                }
                if (output != null && itemSize <= MAX_ITEM_BYTES) files[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        val results = files.filterKeys { it.endsWith(".po", true) }
            .mapNotNull { (name, data) -> parsePosition(name, data) }.toMutableList()
        files.filterKeys { it.endsWith("_names.list", true) }.forEach { (name, data) ->
            val prefix = name.substringBeforeLast('/', "")
            data.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, path ->
                if (path.trim().endsWith(".po", true)) {
                    val tag = if (prefix.isEmpty()) "${index + 1}.tag" else "$prefix/${index + 1}.tag"
                    files[tag]?.let { parsePosition(path.trim(), it) }?.let(results::add)
                }
            }
        }
        return results
    }
}
