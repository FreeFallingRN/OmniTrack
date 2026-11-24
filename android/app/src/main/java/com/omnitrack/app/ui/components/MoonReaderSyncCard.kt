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
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.MoonReaderImport
import com.omnitrack.app.utils.MoonReaderScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MoonReaderSyncCard(viewModel: SeriesViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE) }
    val entries by viewModel.mediaEntries.collectAsState()
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(prefs.getString("moon_reader_folder", null)) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MoonReaderScan?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }

    fun scan(uri: Uri, tree: Boolean) {
        scope.launch {
            busy = true
            try {
                val result = MoonReaderImport.scan(context, uri, tree, entries)
                selected = emptySet()
                values = result.matches.associate { match ->
                    val unit = match.entry.unitName.trim().lowercase()
                    match.entry.id to if (unit in setOf("%", "percent", "percentage")) {
                        match.progress.percent.toString()
                    } else ""
                }
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
                val previous = folder
                folder = uri.toString()
                prefs.edit().putString("moon_reader_folder", folder).apply()
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
            } catch (e: SecurityException) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy) {
                    Text("Choose folder")
                }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text("Choose file")
                }
            }
            if (folder != null) {
                TextButton(onClick = { scan(Uri.parse(folder), true) }, enabled = !busy) {
                    Text("Scan saved folder")
                }
            }
            if (busy) Text("Working…")
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
                    Text("Percentages cannot be converted to chapters automatically. Enter the desired value in the entry's unit.")
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
                                Text("Moon+: ${match.progress.percent}% (${match.progress.title})")
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
                                    val current = viewModel.mediaEntries.value.firstOrNull { it.id == match.entry.id }
                                    check(current != null && current.currentUnit == match.entry.currentUnit &&
                                        current.unitName == match.entry.unitName) {
                                        "${match.entry.title} changed since the preview; scan again"
                                    }
                                    val value = values.getValue(match.entry.id).toDouble()
                                    viewModel.importMoonReaderProgress(match.entry.id, value)
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
