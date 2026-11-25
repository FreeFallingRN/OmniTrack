package com.omnitrack.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "MoonReaderLaunch"

fun findMoonReaderPackage(context: Context): String? =
    listOf("com.flyersoft.moonreaderp", "com.flyersoft.moonreader")
        .firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

suspend fun openInMoonReader(
    context: Context,
    packageName: String,
    poFileUri: String?,
    poFileName: String?,
    folderUri: String?
) {
    val book = withContext(Dispatchers.IO) {
        runCatching { findSiblingBook(context, poFileUri, poFileName, folderUri) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Unable to find linked book; opening Moon+ Reader", it)
            }
            .getOrNull()
    }
    if (book != null) {
        val extension = book.second.substringAfterLast('.', "").lowercase()
        val mimeType = if (extension == "epub") "application/epub+zip" else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(book.first, mimeType)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { Log.w(TAG, "Unable to open linked book; opening Moon+ Reader", it) }
            .isSuccess
        if (opened) return
    }

    runCatching {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName)) {
            "Moon+ Reader is no longer available"
        }
        context.startActivity(intent)
    }.onFailure {
        Log.e(TAG, "Unable to launch Moon+ Reader", it)
        Toast.makeText(context, "Could not open Moon+ Reader", Toast.LENGTH_LONG).show()
    }
}

private suspend fun findSiblingBook(
    context: Context,
    poFileUri: String?,
    poFileName: String?,
    folderUri: String?
): Pair<Uri, String>? {
    if (poFileUri == null || poFileName == null || folderUri == null ||
        !poFileName.endsWith(".po", ignoreCase = true)) return null
    val tree = Uri.parse(folderUri)
    val position = Uri.parse(poFileUri)
    if (tree.authority != position.authority) return null
    val positionId = DocumentsContract.getDocumentId(position)
    val bookName = poFileName.dropLast(3)
    val pending = ArrayDeque<Pair<String, Int>>()
    pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
    val visited = mutableSetOf<String>()
    var count = 0
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (id, depth) = pending.removeFirst()
        if (!visited.add(id)) continue
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val cursor = context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        ) ?: error("Unable to list Moon+ Reader folder")
        var containsPosition = false
        val books = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                check(++count <= 4000) { "Moon+ Reader folder contains more than 4000 items" }
                val childId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDirectory) {
                    if (depth < 8) pending.add(childId to depth + 1)
                } else {
                    if (childId == positionId) containsPosition = true
                    if (name == bookName) books.add(childId)
                }
            }
        }
        if (containsPosition) {
            return books.singleOrNull()?.let {
                DocumentsContract.buildDocumentUriUsingTree(tree, it) to bookName
            }
        }
    }
    return null
}
