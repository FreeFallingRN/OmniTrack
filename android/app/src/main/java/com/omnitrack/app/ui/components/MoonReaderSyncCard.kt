package com.omnitrack.app.ui.components

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.MoonReaderImport
import com.omnitrack.app.utils.MoonReaderScan
import com.omnitrack.app.utils.MoonReaderSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun folderDisplayName(context: Context, uri: Uri): String {
    val documentId = DocumentsContract.getTreeDocumentId(uri)
    val fallback = documentId.substringAfterLast('/').substringAfterLast(':')
        .takeUnless { it.isBlank() || it.contains("doc=") } ?: "Selected folder"
    return try {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        context.contentResolver.query(
            document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        } ?: fallback
    } catch (e: Exception) {
        Log.w("MoonReaderSync", "Could not resolve folder name", e)
        fallback
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoonReaderSyncCard(viewModel: SeriesViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE) }
    val entries by viewModel.mediaEntries.collectAsState()
    val syncing by viewModel.moonReaderBusy.collectAsState()
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(prefs.getString("moon_reader_folder", null)) }
    var folderName by remember { mutableStateOf(prefs.getString("moon_reader_folder_name", null)) }
    var booksFolder by remember { mutableStateOf(prefs.getString("moon_reader_books_folder", null)) }
    var booksFolderName by remember { mutableStateOf(prefs.getString("moon_reader_books_folder_name", null)) }
    fun hasAccess(value: String?): Boolean = value != null &&
        context.contentResolver.persistedUriPermissions.any { it.uri == Uri.parse(value) && it.isReadPermission }
    var folderAccess by remember { mutableStateOf(hasAccess(folder)) }
    var booksFolderAccess by remember { mutableStateOf(hasAccess(booksFolder)) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MoonReaderScan?>(null) }
    var previewFolder by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }
    var linksVersion by remember { mutableStateOf(0) }
    var showLinks by remember { mutableStateOf(false) }
    val linkedEntries = remember(entries, linksVersion) {
        entries.filter { prefs.getString("moon_reader_file_uri_${it.id}", null) != null }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("moon_reader_file_uri_") ||
                key.startsWith("moon_reader_chapter_offset_")) linksVersion++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(folder, folderAccess) {
        val savedFolder = folder
        if (savedFolder != null && folderAccess && folderName == null) {
            try {
                val name = withContext(Dispatchers.IO) { folderDisplayName(context, Uri.parse(savedFolder)) }
                if (folder == savedFolder) {
                    check(prefs.edit().putString("moon_reader_folder_name", name).commit()) {
                        "Could not save the folder name"
                    }
                    folderName = name
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Could not resolve folder name: ${e.message}"
            }
        }
    }

    DisposableEffect(context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                folderAccess = hasAccess(folder)
                booksFolderAccess = hasAccess(booksFolder)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    fun scan(uri: Uri) {
        scope.launch {
            busy = true
            try {
                val result = MoonReaderImport.scan(context, uri, true, entries)
                val linkedUris = entries.mapNotNull {
                    prefs.getString("moon_reader_file_uri_${it.id}", null)
                }.toSet()
                val matches = result.matches.filter {
                    MoonReaderSync.isChapterUnit(it.entry.unitName) &&
                        it.progress.fileUri != null && it.progress.fileName != null &&
                        prefs.getString("moon_reader_file_uri_${it.entry.id}", null) == null &&
                        it.progress.fileUri.toString() !in linkedUris
                }
                selected = emptySet()
                values = matches.associate { match ->
                    val key = "moon_reader_chapter_offset_${match.entry.id}"
                    val chapter = match.progress.spineChapterIndex.toDouble() + 1.0
                    val offset = if (prefs.contains(key)) {
                        Double.fromBits(prefs.getLong(key, 0L)).also {
                            check(it.isFinite()) { "Invalid saved chapter offset for ${match.entry.title}" }
                        }
                    } else if (match.entry.currentUnit > 0.0) {
                        (match.entry.currentUnit - chapter).coerceAtLeast(0.0)
                    } else 0.0
                    match.entry.id to (offset + chapter).toString()
                }
                previewFolder = uri.toString()
                preview = result.copy(matches = matches)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Moon+ Reader scan failed: ${e.message ?: "Unknown error"}"
            } finally {
                busy = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                var ready = false
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(hasAccess(uri.toString())) { "Folder read permission was not retained" }
                    val previous = folder
                    val name = withContext(Dispatchers.IO) { folderDisplayName(context, uri) }
                    check(prefs.edit().putString("moon_reader_folder", uri.toString())
                        .putString("moon_reader_folder_name", name).commit()) {
                        "Could not save the selected folder"
                    }
                    folder = uri.toString()
                    folderName = name
                    folderAccess = true
                    if (previous != null && previous != folder && previous != booksFolder) {
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: SecurityException) {
                            Log.w("MoonReaderSync", "Could not release previous folder permission", e)
                        }
                    }
                    ready = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Could not retain folder access: ${e.message}"
                } finally {
                    busy = false
                }
                if (ready) scan(uri)
            }
        }
    }

    val booksFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(hasAccess(uri.toString())) { "Books folder read permission was not retained" }
                    val previous = booksFolder
                    val name = withContext(Dispatchers.IO) { folderDisplayName(context, uri) }
                    check(prefs.edit().putString("moon_reader_books_folder", uri.toString())
                        .putString("moon_reader_books_folder_name", name).commit()) {
                        "Could not save the selected books folder"
                    }
                    booksFolder = uri.toString()
                    booksFolderName = name
                    booksFolderAccess = true
                    if (previous != null && previous != booksFolder && previous != folder) {
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: SecurityException) {
                            Log.w("MoonReaderSync", "Could not release previous books folder permission", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Could not retain books folder access: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Moon+ Reader progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (folder == null) "No folder linked"
                else if (folderAccess) "Folder linked: ${folderName ?: "Selected folder"}"
                else "Folder access lost. Change folder to grant access again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            val actionPadding = PaddingValues(horizontal = 8.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        booksFolder == null -> "Books folder: Not set"
                        !booksFolderAccess -> "Books folder: Access lost"
                        else -> "Books folder: ${booksFolderName ?: "Selected folder"}"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = { booksFolderPicker.launch(null) },
                    enabled = !busy && !syncing,
                    colors = actionColors,
                    contentPadding = actionPadding
                ) {
                    Text(if (booksFolder == null) "Set folder" else "Change")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !busy && !syncing,
                    colors = actionColors,
                    contentPadding = actionPadding
                ) {
                    Text(if (folder == null) "Select folder" else "Change folder")
                }
                if (folder != null) {
                    TextButton(
                        onClick = { viewModel.syncMoonReader(manual = true) },
                        enabled = !busy && !syncing,
                        colors = actionColors,
                        contentPadding = actionPadding
                    ) {
                        Text("Sync now")
                    }
                    TextButton(
                        onClick = { scan(Uri.parse(folder)) },
                        enabled = !busy && !syncing && folderAccess,
                        colors = actionColors,
                        contentPadding = actionPadding
                    ) {
                        Text("Link books")
                    }
                }
                TextButton(
                    onClick = { showLinks = !showLinks },
                    enabled = linkedEntries.isNotEmpty(),
                    colors = actionColors,
                    contentPadding = actionPadding
                ) {
                    Text("${linkedEntries.size} linked ${if (linkedEntries.size == 1) "book" else "books"}" +
                        if (showLinks && linkedEntries.isNotEmpty()) " \u25b4" else " \u25be")
                }
            }
            if (showLinks && linkedEntries.isNotEmpty()) {
                linkedEntries.forEach { entry ->
                    val offsetKey = "moon_reader_chapter_offset_${entry.id}"
                    val offset = if (prefs.contains(offsetKey)) {
                        Double.fromBits(prefs.getLong(offsetKey, 0L)).takeIf { it.isFinite() }
                    } else null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    !MoonReaderSync.isChapterUnit(entry.unitName) -> "No longer uses chapters"
                                    offset == null -> "Chapter offset unavailable"
                                    else -> "Chapter offset: ${formatUnit(offset)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = !busy && !syncing,
                            colors = actionColors,
                            contentPadding = actionPadding,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        viewModel.unlinkMoonReaderBook(entry.id)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        message = "Could not unlink ${entry.title}: ${e.message ?: "Unknown error"}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("Unlink") }
                    }
                }
            }
            if (busy || syncing) Text("Working…")
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Moon+ Reader") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
    preview?.let { result ->
        val allIds = result.matches.map { it.entry.id }.toSet()
        val validIds = result.matches.filter { match ->
            val number = values[match.entry.id]?.toDoubleOrNull()
            number != null && number.isFinite() && number >= 0.0
        }.map { it.entry.id }.toSet()
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("Link Moon+ Reader books") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("${result.matches.size} unlinked matching ${if (result.matches.size == 1) "book" else "books"} found in folder")
                    if (result.hasProblems) {
                        Text(result.message, style = MaterialTheme.typography.bodySmall)
                    }
                    if (result.matches.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected.containsAll(allIds),
                                onCheckedChange = { checked -> selected = if (checked) allIds else emptySet() },
                                enabled = !busy
                            )
                            Text("Select all")
                        }
                    }
                    result.matches.forEach { match ->
                        val id = match.entry.id
                        val input = values[id].orEmpty()
                        val valid = id in validIds
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + id else selected - id
                                },
                                enabled = !busy
                            )
                            Column(Modifier.weight(1f)) {
                                Text(match.entry.title, style = MaterialTheme.typography.titleSmall)
                                Text("Moon+ spine chapter index: ${match.progress.spineChapterIndex} (${match.progress.title})")
                                Text("Current: ${match.entry.currentUnit} ${match.entry.unitName}")
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = {
                                        values = values + (id to it)
                                        selected = selected + id
                                    },
                                    label = { Text("New ${match.entry.unitName}") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    enabled = !busy,
                                    isError = input.isNotEmpty() && !valid
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && selected.isNotEmpty() && validIds.containsAll(selected),
                    onClick = {
                        scope.launch {
                            busy = true
                            var applied = 0
                            try {
                                for (match in result.matches.filter { it.entry.id in selected }) {
                                    val value = values.getValue(match.entry.id).toDouble()
                                    viewModel.importMoonReaderProgress(match, value, previewFolder)
                                    applied++
                                }
                                preview = null
                                Toast.makeText(context, "Linked $applied ${if (applied == 1) "book" else "books"}.", Toast.LENGTH_SHORT).show()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                preview = null
                                message = "Applied $applied updates before the import failed: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Apply selected") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }, enabled = !busy) { Text("Cancel") }
            }
        )
    }
}
