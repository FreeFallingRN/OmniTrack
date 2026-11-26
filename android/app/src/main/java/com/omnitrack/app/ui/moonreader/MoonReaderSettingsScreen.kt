package com.omnitrack.app.ui.moonreader

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.components.formatUnit
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.MoonReaderScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.omnitrack.app.utils.MOON_READER_FOLDER_EXPIRED
import com.omnitrack.app.utils.moonReaderProviderCall

internal suspend fun folderDisplayName(context: Context, uri: Uri): String {
    val documentId = DocumentsContract.getTreeDocumentId(uri)
    val fallback = documentId.substringAfterLast('/').substringAfterLast(':')
        .takeUnless { it.isBlank() || it.contains("doc=") } ?: "Selected folder"
    return try {
        moonReaderProviderCall(3_000L) {
            val document = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            context.contentResolver.query(
                document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            } ?: fallback
        } ?: fallback.also { Log.w("MoonReaderSync", "Folder name lookup timed out") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("MoonReaderSync", "Could not resolve folder name", e)
        fallback
    }
}

private fun initialFolderDocumentUri(savedFolder: String?): Uri? = savedFolder?.let {
    runCatching {
        val treeUri = Uri.parse(it)
        require(treeUri.scheme == "content" && !treeUri.authority.isNullOrBlank())
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    }.getOrNull()
}

private fun folderLocation(context: Context, savedFolder: String, name: String?): String {
    val uri = Uri.parse(savedFolder)
    val authority = uri.authority
    val provider = when {
        authority == "com.android.externalstorage.documents" -> "Device storage"
        authority == "com.google.android.apps.docs.storage" ||
            authority == "com.google.android.apps.docs.storage.legacy" -> "Google Drive"
        authority != null -> context.packageManager.resolveContentProvider(authority, 0)
            ?.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: authority
        else -> "Unknown provider"
    }
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    val path = if (authority == "com.android.externalstorage.documents" && documentId != null) {
        val root = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")
        val rootName = if (root == "primary") "Internal storage" else root
        listOf(rootName, relativePath).filter { it.isNotBlank() }.joinToString(" / ")
    } else {
        listOfNotNull(name?.takeIf { it.isNotBlank() }, documentId?.takeIf { it.isNotBlank() })
            .distinct().joinToString(" · ")
    }
    return "$provider · ${path.ifBlank { name ?: "Unknown folder" }}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoonReaderSettingsScreen(viewModel: SeriesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalFeatureServices.current.settings
    val moonReader = LocalFeatureServices.current.moonReader
    val settingsState by settings.state.collectAsState()
    val entries by viewModel.mediaEntries.collectAsState()
    val syncing by moonReader.moonReaderBusy.collectAsState()
    val needsProgressReauth by moonReader.needsProgressFolderReauth.collectAsState()
    val needsBooksReauth by moonReader.needsBooksFolderReauth.collectAsState()
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(settingsState.progressFolder) }
    var folderName by remember { mutableStateOf(settingsState.progressFolderName) }
    var booksFolder by remember { mutableStateOf(settingsState.booksFolder) }
    var booksFolderName by remember { mutableStateOf(settingsState.booksFolderName) }
    fun hasAccess(value: String?): Boolean = value != null &&
        context.contentResolver.persistedUriPermissions.any { it.uri == Uri.parse(value) && it.isReadPermission }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MoonReaderScan?>(null) }
    var previewFolder by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }
    val linkedEntries = remember(entries, settingsState.revision) {
        entries.filter { settings.linkedFileUri(it.id) != null }
    }

    LaunchedEffect(folder) {
        val savedFolder = folder
        if (savedFolder != null && folderName == null) {
            try {
                val name = folderDisplayName(context, Uri.parse(savedFolder))
                if (folder == savedFolder) {
                    settings.setProgressFolderName(name)
                    folderName = name
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Could not resolve folder name: ${e.message}"
            }
        }
    }

    LaunchedEffect(folder, booksFolder) {
        folder?.let { moonReader.checkMoonReaderFolder(it, books = false) }
        booksFolder?.let { moonReader.checkMoonReaderFolder(it, books = true) }
    }

    DisposableEffect(context, folder, booksFolder) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    folder?.let { moonReader.checkMoonReaderFolder(it, books = false) }
                    booksFolder?.let { moonReader.checkMoonReaderFolder(it, books = true) }
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    fun scan(uri: Uri) {
        scope.launch {
            busy = true
            try {
                val (result, calibratedValues) = moonReader.scan(uri, entries) ?: return@launch
                selected = emptySet()
                values = calibratedValues
                previewFolder = uri.toString()
                preview = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                moonReader.setMoonReaderFolderAccess(uri.toString(), books = false, valid = false)
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
                    check(moonReader.checkMoonReaderFolder(uri.toString(), books = false)) { MOON_READER_FOLDER_EXPIRED }
                    val previous = folder
                    val name = folderDisplayName(context, uri)
                    settings.setProgressFolder(uri.toString(), name)
                    folder = uri.toString()
                    folderName = name
                    moonReader.setMoonReaderFolderAccess(uri.toString(), books = false, valid = true)
                    moonReader.updateMoonReaderSchedule()
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
                    check(moonReader.checkMoonReaderFolder(uri.toString(), books = true)) { MOON_READER_FOLDER_EXPIRED }
                    val previous = booksFolder
                    val name = folderDisplayName(context, uri)
                    settings.setBooksFolder(uri.toString(), name)
                    booksFolder = uri.toString()
                    booksFolderName = name
                    moonReader.setMoonReaderFolderAccess(uri.toString(), books = true, valid = true)
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

    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    val actionPadding = PaddingValues(horizontal = 8.dp)
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Moon+ Reader", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Progress Backup / Sync Folder (.po files)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    folder?.let { folderLocation(context, it, folderName) } ?: "No progress folder selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (needsProgressReauth && folder != null) {
                    TextButton(
                        onClick = { folderPicker.launch(initialFolderDocumentUri(folder)) },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = actionPadding
                    ) { Text("Access expired — re-select folder") }
                }
                TextButton(
                    onClick = { folderPicker.launch(initialFolderDocumentUri(folder)) },
                    enabled = !busy && (!syncing || needsProgressReauth),
                    colors = actionColors,
                    contentPadding = actionPadding
                ) { Text("Change progress folder") }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("EPUB Storage Folder (.epub files)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    booksFolder?.let { folderLocation(context, it, booksFolderName) } ?: "No EPUB folder selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (needsBooksReauth && booksFolder != null) {
                    TextButton(
                        onClick = { booksFolderPicker.launch(initialFolderDocumentUri(booksFolder)) },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = actionPadding
                    ) { Text("Access expired — re-select folder") }
                }
                TextButton(
                    onClick = { booksFolderPicker.launch(initialFolderDocumentUri(booksFolder)) },
                    enabled = !busy && !syncing,
                    colors = actionColors,
                    contentPadding = actionPadding
                ) { Text("Change EPUB folder") }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sync & Linked Books", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (needsProgressReauth) {
                    Text("Re-select the progress folder to sync or link books.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else if (folder == null) {
                    Text("Select a progress folder to sync or link books.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { moonReader.syncMoonReader(manual = true) },
                        enabled = folder != null && !needsProgressReauth && !busy && !syncing,
                        colors = actionColors,
                        contentPadding = actionPadding
                    ) {
                        Text("Sync now")
                    }
                    TextButton(
                        onClick = { folder?.let { scan(Uri.parse(it)) } },
                        enabled = folder != null && !busy && !syncing && !needsProgressReauth,
                        colors = actionColors,
                        contentPadding = actionPadding
                    ) {
                        Text("Link books")
                    }
                }
                Text("${linkedEntries.size} linked ${if (linkedEntries.size == 1) "book" else "books"}", style = MaterialTheme.typography.bodyMedium)
                linkedEntries.forEach { entry ->
                    val offset = settings.chapterOffset(entry.id)?.takeIf { it.isFinite() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    !moonReader.isChapterUnit(entry.unitName) -> "No longer uses chapters"
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
                                        moonReader.unlinkMoonReaderBook(entry.id)
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
                if (busy || syncing) Text("Working…")
            }
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
                                    moonReader.importMoonReaderProgress(match, value, previewFolder)
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
