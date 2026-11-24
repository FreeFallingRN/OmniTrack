package com.omnitrack.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.MoonReaderImport
import com.omnitrack.app.utils.MoonReaderScan
import com.omnitrack.app.utils.MoonReaderSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MoonReaderSyncCard(viewModel: SeriesViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE) }
    val entries by viewModel.mediaEntries.collectAsState()
    val syncing by viewModel.moonReaderBusy.collectAsState()
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(prefs.getString("moon_reader_folder", null)) }
    fun hasAccess(value: String?): Boolean = value != null &&
        context.contentResolver.persistedUriPermissions.any { it.uri == Uri.parse(value) && it.isReadPermission }
    var folderAccess by remember { mutableStateOf(hasAccess(folder)) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MoonReaderScan?>(null) }
    var previewFolder by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }

    DisposableEffect(context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) folderAccess = hasAccess(folder)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    fun scan(uri: Uri, tree: Boolean) {
        scope.launch {
            busy = true
            try {
                val result = MoonReaderImport.scan(context, uri, tree, entries)
                selected = emptySet()
                values = result.matches.associate { match ->
                    val unit = match.entry.unitName.trim().lowercase()
                    val value = when {
                        MoonReaderSync.isChapterUnit(unit) -> {
                            val key = "moon_reader_chapter_offset_${match.entry.id}"
                            val chapter = match.progress.spineChapterIndex.toDouble() + 1.0
                            val offset = if (prefs.contains(key)) {
                                Double.fromBits(prefs.getLong(key, 0L)).also {
                                    check(it.isFinite()) { "Invalid saved chapter offset for ${match.entry.title}" }
                                }
                            } else if (match.entry.currentUnit > 0.0) {
                                (match.entry.currentUnit - chapter).coerceAtLeast(0.0)
                            } else 0.0
                            (offset + chapter).toString()
                        }
                        unit in setOf("%", "percent", "percentage") -> match.progress.percent.toString()
                        else -> ""
                    }
                    match.entry.id to value
                }
                previewFolder = if (tree) uri.toString() else null
                preview = result
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
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                check(hasAccess(uri.toString())) { "Folder read permission was not retained" }
                val previous = folder
                check(prefs.edit().putString("moon_reader_folder", uri.toString()).commit()) {
                    "Could not save the selected folder"
                }
                folder = uri.toString()
                folderAccess = true
                if (previous != null && previous != folder) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        android.util.Log.w("MoonReaderSync", "Could not release previous folder permission", e)
                    }
                }
                scan(uri, true)
            } catch (e: Exception) {
                message = "Could not retain folder access: ${e.message}"
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scan(uri, false)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Moon+ Reader progress", style = MaterialTheme.typography.titleMedium)
            Text("Import .po sync files or supported backup positions.", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (folder == null) "No folder linked"
                else if (folderAccess) "Folder linked: ${Uri.parse(folder).lastPathSegment}"
                else "Folder access lost. Change folder to grant access again."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy && !syncing) {
                    Text(if (folder == null) "Choose folder" else "Change folder")
                }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy && !syncing) {
                    Text("Choose file")
                }
            }
            if (folder != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.syncMoonReader(manual = true) }, enabled = !busy && !syncing) {
                        Text("Sync now")
                    }
                    TextButton(onClick = { scan(Uri.parse(folder), true) }, enabled = !busy && !syncing && folderAccess) {
                        Text("Link books")
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
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("Moon+ Reader preview") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(result.message)
                    Text("Chapter entries use the Moon+ spine index plus one and a saved per-entry offset. Adjust the chapter to calibrate future imports.")
                    Text("Applying a chapter match from a folder .po file links it for automatic sync. Backups and individual files are imported only.")
                    result.matches.forEach { match ->
                        val id = match.entry.id
                        val input = values[id].orEmpty()
                        val number = input.toDoubleOrNull()
                        val valid = number != null && number.isFinite() && number >= 0.0 &&
                            (match.entry.unitName.trim().lowercase() !in setOf("%", "percent", "percentage") || number <= 100.0)
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + id else selected - id
                                },
                                enabled = !busy && valid
                            )
                            Column(Modifier.weight(1f)) {
                                Text(match.entry.title, style = MaterialTheme.typography.titleSmall)
                                Text("Moon+ spine chapter index: ${match.progress.spineChapterIndex} (${match.progress.title})")
                                if (!MoonReaderSync.isChapterUnit(match.entry.unitName)) {
                                    Text("Moon+: ${match.progress.percent}%")
                                }
                                Text("Current: ${match.entry.currentUnit} ${match.entry.unitName}")
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = {
                                        values = values + (id to it)
                                        selected = selected - id
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
                    enabled = !busy && selected.isNotEmpty(),
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
                                message = "Applied $applied updates locally; Supabase sync was attempted."
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
