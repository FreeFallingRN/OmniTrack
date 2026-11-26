package com.omnitrack.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var showTokenDialog by remember { mutableStateOf(false) }
    var batchTitles by remember { mutableStateOf<List<MangaBakaTitle>?>(null) }
    var personalToken by remember { mutableStateOf("") }
    val entriesById = remember(entries) { entries.associateBy { it.id } }
    val availableEntries = remember(entries, state.links) {
        val linkedIds = state.links.values.map { it.entryId }.toSet()
        entries.filter {
            it.id !in linkedIds &&
                (it.unitName.trim().equals("chapter", true) || it.unitName.trim().equals("chapters", true))
        }
    }
    val matchState = remember(state.titles, state.links, availableEntries) {
        mutableStateOf<Map<Long, MediaEntry>>(emptyMap())
    }
    LaunchedEffect(state.titles, state.links, availableEntries) {
        val allTitles = state.titles
        val links = state.links
        matchState.value = withContext(Dispatchers.Default) {
            allTitles.mapNotNull { title ->
                ensureActive()
                if (links.containsKey(title.series.id)) null
                else findMangaBakaMatch(title, availableEntries)?.let { title.series.id to it }
            }.toMap()
        }
    }
    val matches = matchState.value
    val titles = remember(state.titles, state.links, search, filter) {
        state.titles.filter { title ->
            val linked = state.links.containsKey(title.series.id)
            title.title.contains(search, ignoreCase = true) &&
                (filter == "All" || (filter == "Linked" && linked) || (filter == "Unlinked" && !linked))
        }
    }
    val unlinkedTitles = titles.filterNot { state.links.containsKey(it.series.id) }

    DisposableEffect(context, viewModel) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncMangaBakaProgress()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        TextButton(enabled = !state.busy, onClick = { showTokenDialog = true }) {
            Text("Connect with personal access token")
        }
        if (showTokenDialog) {
            AlertDialog(
                onDismissRequest = {
                    personalToken = ""
                    showTokenDialog = false
                },
                title = { Text("MangaBaka personal access token") },
                text = {
                    OutlinedTextField(
                        value = personalToken,
                        onValueChange = { personalToken = it },
                        label = { Text("Personal access token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(enabled = personalToken.isNotBlank(), onClick = {
                        val token = personalToken
                        personalToken = ""
                        showTokenDialog = false
                        scope.launch { viewModel.mangaBaka.connectPersonalToken(token) }
                    }) { Text("Connect") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        personalToken = ""
                        showTokenDialog = false
                    }) { Text("Cancel") }
                }
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(enabled = state.connected && !state.busy, onClick = {
                viewModel.syncAndMatchMangaBakaLibrary()
            }) { Text("Sync & match library") }
            Button(enabled = state.connected && state.links.isNotEmpty() && !state.busy, onClick = {
                viewModel.syncMangaBakaProgress(manual = true)
            }) { Text("Sync now") }
            Button(enabled = state.connected && matches.isNotEmpty() && !state.busy, onClick = {
                viewModel.linkAllMatchedMangaBakaEntries(
                    state.titles.mapNotNull { title -> matches[title.series.id]?.let { title to it.id } }
                )
            }) { Text("Link all matched (${matches.size})") }
            Button(enabled = state.connected && unlinkedTitles.isNotEmpty() && !state.busy, onClick = {
                batchTitles = unlinkedTitles
            }) { Text("Batch import all (${unlinkedTitles.size})") }
            if (state.batchImporting) {
                TextButton(enabled = !state.batchCancelRequested, onClick = {
                    viewModel.mangaBaka.cancelBatchImport()
                }) { Text(if (state.batchCancelRequested) "Stopping..." else "Cancel import") }
            }
        }
        if (state.titles.isNotEmpty()) {
            Text(
                "${state.titles.count { state.links.containsKey(it.series.id) }}/${state.titles.size} linked. " +
                    "Link all uses matches across the entire library; duplicate targets are skipped.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.batchMessage.isNotBlank()) Text(state.batchMessage, style = MaterialTheme.typography.bodyMedium)
        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Search titles") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("All", "Linked", "Unlinked").forEach { option ->
                TextButton(onClick = { filter = option }) {
                    Text(if (filter == option) "[$option]" else option)
                }
            }
        }
        }
        }
        run {
            items(titles, key = { it.series.id }) { title ->
                val link = state.links[title.series.id]
                val match = matches[title.series.id]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsyncImage(
                                model = title.series.cover?.x250 ?: title.series.cover?.raw,
                                contentDescription = null,
                                modifier = Modifier.size(width = 48.dp, height = 70.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Chapter: ${title.entry.progress?.toString() ?: "Not set"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    if (link == null) "Not linked" else
                                        "Linked: ${entriesById[link.entryId]?.title ?: "Entry unavailable"} (offset ${link.offset})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (match != null) {
                                    Text(
                                        "Match: ${match.title}",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (link == null) {
                                if (match != null) {
                                    TextButton(
                                        enabled = !state.busy,
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        onClick = { viewModel.linkMangaBakaEntry(title, match.id, 0.0) }
                                    ) { Text("Link match", style = MaterialTheme.typography.labelMedium) }
                                }
                                TextButton(enabled = !state.busy, onClick = {
                                    if (title.series.type == "other") otherTypeTitle = title
                                    else viewModel.addMangaBakaEntry(title)
                                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("Add as new", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(
                                    enabled = !state.busy, onClick = { selected = title },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) { Text("Link existing", style = MaterialTheme.typography.labelMedium) }
                            } else {
                                TextButton(enabled = !state.busy, onClick = {
                                    viewModel.unlinkMangaBakaEntry(title)
                                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("Unlink", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            TextButton(
                                modifier = Modifier.semantics { contentDescription = "View on MangaBaka" },
                                onClick = {
                                    try {
                                        val uri = Uri.parse(mangaBakaSeriesUrl(title))
                                        require(uri.scheme == "https" && uri.host == "mangabaka.org")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    } catch (e: Exception) {
                                        Log.w("MangaBaka", "Could not open series page")
                                        localError = "Could not open the MangaBaka series page."
                                    }
                                }, contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text("View", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    batchTitles?.let { selectedTitles ->
        AlertDialog(
            onDismissRequest = { batchTitles = null },
            title = { Text("Batch import as new entries?") },
            text = {
                Text(
                    "Import ${selectedTitles.size} unlinked titles from the current filters as new entries. " +
                        "Existing matches will not be linked. Titles needing a media type or creator will be reported. " +
                        "Cancellation stops after the current title."
                )
            },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    batchTitles = null
                    viewModel.batchImportMangaBakaEntries(selectedTitles)
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { batchTitles = null }) { Text("Cancel") } }
        )
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
            mangaBakaMatchesType(title, it.type) &&
            (it.unitName.trim().equals("chapter", true) || it.unitName.trim().equals("chapters", true)) &&
            it.title.contains(search, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link ${title.title}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    search, { search = it }, label = { Text("Search entries") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    offsetText, { offsetText = it }, label = { Text("Chapter offset") },
                    isError = offset == null, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "For comics, the offset is added to MangaBaka's chapter on sync. Novel progress is not synced. Linking does not change progress.",
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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
