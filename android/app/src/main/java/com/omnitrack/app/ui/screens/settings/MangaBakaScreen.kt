package com.omnitrack.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.ui.viewmodel.mangaBakaSeriesUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MangaBakaScreen(viewModel: SeriesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.mangaBaka.state.collectAsState()
    val entries by viewModel.mediaEntries.collectAsState()
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<MangaBakaTitle?>(null) }
    var otherTypeTitle by remember { mutableStateOf<MangaBakaTitle?>(null) }
    var localError by remember { mutableStateOf("") }
    val entriesById = remember(entries) { entries.associateBy { it.id } }
    val availableEntries = remember(entries, state.links) {
        val linkedIds = state.links.values.map { it.entryId }.toSet()
        entries.filter {
            it.id !in linkedIds &&
                (it.unitName.trim().equals("chapter", true) || it.unitName.trim().equals("chapters", true))
        }
    }
    val titles = remember(state.titles, state.links, search, filter) {
        state.titles.filter { title ->
            val linked = state.links.containsKey(title.series.id)
            title.title.contains(search, ignoreCase = true) &&
                (filter == "All" || (filter == "Linked" && linked) || (filter == "Unlinked" && !linked))
        }
    }

    DisposableEffect(context, viewModel) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncMangaBakaProgress()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("MangaBaka", style = MaterialTheme.typography.titleLarge)
        }
        Text(if (state.connected) "Connected ${state.username}".trim() else "Connect your MangaBaka account")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.busy, onClick = {
                scope.launch {
                    try {
                        localError = ""
                        val url = viewModel.mangaBaka.authorizationUrl()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("MangaBaka", "Could not start sign-in", e)
                        localError = "Could not open MangaBaka sign-in: ${e.message ?: "unknown error"}"
                    }
                }
            }) { Text(if (state.connected) "Reconnect" else "Connect") }
            Button(enabled = state.connected && !state.busy, onClick = {
                viewModel.fetchMangaBakaLibrary()
            }) { Text("Fetch library") }
            if (state.busy) CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
        Button(enabled = state.connected && state.links.isNotEmpty() && !state.busy, onClick = {
            viewModel.syncMangaBakaProgress(manual = true)
        }) { Text("Sync now") }
        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Search titles") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row {
            listOf("All", "Linked", "Unlinked").forEach { option ->
                TextButton(onClick = { filter = option }) {
                    Text(if (filter == option) "[$option]" else option)
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(titles, key = { it.series.id }) { title ->
                val link = state.links[title.series.id]
                val match = remember(title, link, availableEntries) {
                    if (link == null) findMangaBakaMatch(title, availableEntries) else null
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(
                                model = title.series.cover?.x250 ?: title.series.cover?.raw,
                                contentDescription = null,
                                modifier = Modifier.size(width = 60.dp, height = 88.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(title.title, style = MaterialTheme.typography.titleMedium)
                                Text("MangaBaka chapter: ${title.entry.progress?.toString() ?: "Not set"}")
                                Text(
                                    if (link == null) "Not linked" else
                                        "Linked: ${entriesById[link.entryId]?.title ?: "Entry unavailable"} (offset ${link.offset})"
                                )
                                TextButton(onClick = {
                                    try {
                                        val uri = Uri.parse(mangaBakaSeriesUrl(title))
                                        require(uri.scheme == "https" && uri.host == "mangabaka.org")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    } catch (e: Exception) {
                                        Log.w("MangaBaka", "Could not open series page")
                                        localError = "Could not open the MangaBaka series page."
                                    }
                                }) { Text("View on MangaBaka") }
                            }
                        }
                        if (link == null) {
                            if (match != null) {
                                Text(
                                    "Match found: ${match.title}",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Button(enabled = !state.busy, onClick = {
                                    viewModel.linkMangaBakaEntry(title, match.id, 0.0)
                                }) { Text("Link to \"${match.title}\"") }
                            }
                            Row {
                                TextButton(enabled = !state.busy, onClick = {
                                    if (title.series.type == "other") otherTypeTitle = title
                                    else viewModel.addMangaBakaEntry(title)
                                }) { Text("Add as new entry") }
                                TextButton(enabled = !state.busy, onClick = { selected = title }) {
                                    Text("Link to existing entry")
                                }
                            }
                        } else {
                            TextButton(enabled = !state.busy, onClick = {
                                viewModel.unlinkMangaBakaEntry(title)
                            }) { Text("Unlink") }
                        }
                    }
                }
            }
        }
        Text("Data provided by MangaBaka and its source providers.", style = MaterialTheme.typography.bodySmall)
    }

    otherTypeTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { otherTypeTitle = null },
            title = { Text("Choose a media type") },
            text = { Text("MangaBaka does not specify whether ${title.title} is a novel or comic.") },
            confirmButton = {
                TextButton(onClick = {
                    otherTypeTitle = null
                    viewModel.addMangaBakaEntry(title, "comic")
                }) { Text("Comic") }
            },
            dismissButton = {
                TextButton(onClick = {
                    otherTypeTitle = null
                    viewModel.addMangaBakaEntry(title, "novel")
                }) { Text("Novel") }
            }
        )
    }

    selected?.let { title ->
        LinkMangaBakaDialog(
            title = title,
            entries = entries,
            linkedIds = state.links.values.map { it.entryId }.toSet(),
            onDismiss = { selected = null },
            onLink = { entryId, offset ->
                selected = null
                viewModel.linkMangaBakaEntry(title, entryId, offset)
            }
        )
    }
}

@Composable
private fun LinkMangaBakaDialog(
    title: MangaBakaTitle,
    entries: List<MediaEntry>,
    linkedIds: Set<String>,
    onDismiss: () -> Unit,
    onLink: (String, Double) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var offsetText by remember { mutableStateOf("0") }
    val offset = offsetText.toDoubleOrNull()?.takeIf { it.isFinite() }
    val available = entries.filter {
        it.id !in linkedIds &&
            (it.unitName.trim().equals("chapter", true) || it.unitName.trim().equals("chapters", true)) &&
            it.title.contains(search, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link ${title.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search entries") }, singleLine = true)
                OutlinedTextField(
                    offsetText, { offsetText = it }, label = { Text("Chapter offset") },
                    isError = offset == null, singleLine = true
                )
                Text("The offset is added to MangaBaka's chapter on sync; linking does not change progress.")
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(available, key = { it.id }) { entry ->
                        TextButton(onClick = { selectedId = entry.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (entry.id == selectedId) "[${entry.title}]" else entry.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selectedId != null && offset != null, onClick = {
                val id = selectedId
                if (id != null && offset != null) onLink(id, offset)
            }) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
