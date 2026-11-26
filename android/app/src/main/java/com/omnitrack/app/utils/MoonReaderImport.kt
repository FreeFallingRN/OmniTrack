package com.omnitrack.app.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
    val lastModified: Long? = null
)
data class MoonReaderMatch(val entry: MediaEntry, val progress: MoonReaderProgress)
data class MoonReaderScan(val matches: List<MoonReaderMatch>, val message: String, val hasProblems: Boolean)
data class MoonReaderFile(val uri: String, val name: String, val lastModified: Long, val timestamp: Long)

object MoonReaderImport {
    private const val MAX_FILES = 4000
    private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_ITEM_BYTES = 64 * 1024
    private val position = Regex("""^(\d+)\*(\d+)@\d+#\d+:([0-9]+(?:\.[0-9]+)?)%""")
    private val bookExtension = Regex("""\.(epub|pdf|mobi|azw3?|fb2|txt|cbz|cbr)$""", RegexOption.IGNORE_CASE)
    private data class FileInfo(val uri: Uri, val name: String, val lastModified: Long?)

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

            fun addFile(file: Uri, name: String, lastModified: Long?) {
                if (linkedFiles != null) {
                    if (!name.endsWith(".po", true) ||
                        (name !in linkedNames.orEmpty() && file.toString() !in linkedUris.orEmpty())) return
                } else if (!name.endsWith(".po", true) &&
                    !name.endsWith(".mrpro", true) && !name.endsWith(".mrstd", true) &&
                    !name.endsWith(".zip", true)) return
                files.add(FileInfo(file, name, lastModified))
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
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED
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
                                    if (it.isNull(3)) null else it.getLong(3)
                                )
                            }
                        }
                    }
                }
            } else {
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                addFile(uri, name, null)
            }

            var ambiguous = 0
            var unmatched = 0
            val linkedCandidates = linkedFiles?.let { links ->
                entries.mapNotNull { entry ->
                    val link = links[entry.id] ?: return@mapNotNull null
                    val exact = files.filter { it.uri.toString() == link.uri }
                    val named = if (links.values.count { it.name == link.name } == 1) {
                        files.filter { file ->
                            file.name == link.name &&
                                links.none { (id, other) ->
                                    id != entry.id && other.uri == file.uri.toString()
                                }
                        }
                    } else emptyList()
                    val matching = if (exact.isNotEmpty()) exact else named
                    when (matching.size) {
                        0 -> { unmatched++; null }
                        1 -> entry.id to matching.single()
                        else -> { ambiguous++; null }
                    }
                }.toMap()
            }
            val filesToRead = if (linkedFiles != null && linkedCandidates != null) {
                linkedCandidates.filter { (id, file) ->
                    val link = linkedFiles.getValue(id)
                    file.uri.toString() != link.uri || file.name != link.name || file.lastModified == null ||
                        file.lastModified <= 0 || file.lastModified != link.lastModified || link.timestamp < 0
                }.values.distinctBy { it.uri }
            } else files
            val semaphore = Semaphore(16)
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
                                            fileUri = file.uri, fileName = file.name, lastModified = file.lastModified
                                        ))
                                    } else readArchive(it)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("MoonReaderImport", "Unable to read ${file.name}", e)
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()
            }
            problems += results.count { it.isEmpty() }
            val found = results.flatten()
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
                    0 -> { unmatched++; null }
                    1 -> MoonReaderMatch(matches.single(), item)
                    else -> { ambiguous++; null }
                }
            }
            val matches = candidates.groupBy { it.entry.id }.values.mapNotNull { group ->
                val directFiles = if (tree) group.filter { it.progress.fileUri != null } else emptyList()
                val preferred = directFiles.ifEmpty { group }
                val latest = preferred.maxOf { it.progress.timestamp }
                val newest = preferred.filter { it.progress.timestamp == latest }
                if (newest.map { it.progress.spineChapterIndex to it.progress.percent }.distinct().size > 1 ||
                    (tree && newest.mapNotNull { it.progress.fileUri }.distinct().size > 1)) {
                    ambiguous++
                    null
                } else newest.first()
            }.sortedBy { it.entry.title.lowercase(Locale.ROOT) }
            MoonReaderScan(
                matches,
                "Found ${found.size} positions in ${filesToRead.size} files; $unmatched unmatched, " +
                    "$ambiguous ambiguous, $problems unreadable or unsupported.",
                unmatched > 0 || ambiguous > 0 || problems > 0
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
            MoonReaderProgress(it, spineChapterIndex, percent, timestamp)
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
