package com.omnitrack.app.ui.anilist

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.AniListLibraryEntry
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.repository.PartialMediaEntryCreateException
import com.omnitrack.app.feature.anilist.matching.findAniListMatch
import com.omnitrack.app.ui.components.SearchablePicker
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.library.editor.MediaEntryEditorSheet
import com.omnitrack.app.ui.library.editor.saveMediaEntry
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.ScrapedCreator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AniListScreen(viewModel: SeriesViewModel, onBack: () -> Unit) {
    val owner = LocalFeatureServices.current.aniList
    val mangaBaka = LocalFeatureServices.current.mangaBaka
    val state by owner.state.collectAsState()
    val entries by viewModel.mediaEntries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AniListLibraryEntry?>(null) }
    var newTitle by remember { mutableStateOf<AniListLibraryEntry?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDisconnect by remember { mutableStateOf(false) }
    var batchTitles by remember { mutableStateOf<List<AniListLibraryEntry>?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val entriesById = remember(entries) { entries.associateBy { it.id } }
    val availableEntries = remember(entries, state.links) {
        val linkedIds = state.links.values.map { it.entryId }.toSet()
        entries.filter { it.id !in linkedIds }
    }
    val matchState = remember(state.titles, state.links, availableEntries) {
        mutableStateOf<Map<Int, MediaEntry>>(emptyMap())
    }
    LaunchedEffect(state.titles, state.links, availableEntries) {
        val allTitles = state.titles
        val links = state.links
        matchState.value = withContext(Dispatchers.Default) {
            allTitles.mapNotNull { title ->
                ensureActive()
                if (title.mediaId in links) null
                else findAniListMatch(title, availableEntries)?.let { title.mediaId to it }
            }.toMap()
        }
    }
    val matches = matchState.value
    val titles = remember(state.titles, state.links, search, filter) {
        state.titles.filter { title ->
            val linked = title.mediaId in state.links
            (title.displayTitle.contains(search, true) ||
                title.media.title.romaji.orEmpty().contains(search, true) ||
                title.media.synonyms.any { it.contains(search, true) }) &&
                (filter == "All" || (filter == "Linked" && linked) || (filter == "Unlinked" && !linked))
        }
    }

    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("AniList", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                AniListCard {
                    Text("Sync & Linked Titles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.titles.count { state.links.containsKey(it.mediaId) }}/${state.titles.size} linked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(enabled = state.connected && state.links.isNotEmpty() && !state.busy,
                            onClick = { owner.sync(manual = true) }) { Text("Sync now") }
                        OutlinedButton(enabled = state.connected && !state.busy, onClick = owner::match) {
                            Text("Sync & match library")
                        }
                        OutlinedButton(enabled = state.connected && !state.busy, onClick = owner::fetch) {
                            Text("Fetch library")
                        }
                    }
                    Text(
                        "Sync & match links matching anime entries. Use Link existing to select an entry manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val unlinked = titles.filterNot { it.mediaId in state.links }
                        OutlinedButton(enabled = state.connected && matches.isNotEmpty() && !state.busy,
                            onClick = owner::linkAllMatched) { Text("Link all matched (${matches.size})") }
                        OutlinedButton(enabled = state.connected && unlinked.isNotEmpty() && !state.busy,
                            onClick = { batchTitles = unlinked }) {
                            Text("Batch import all (${unlinked.size})")
                        }
                        if (state.batchImporting) {
                            TextButton(enabled = !state.batchCancelRequested, onClick = owner::cancelBatchImport) {
                                Text(if (state.batchCancelRequested) "Stopping..." else "Cancel import")
                            }
                        }
                        Text("Link all uses the entire library and skips ambiguous targets.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Working…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                AniListCard {
                    Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (state.connected) "Connected ${state.username}" else "Connect your AniList account",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.connected) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.avatarUrl != null) {
                                AsyncImage(model = state.avatarUrl, contentDescription = "AniList avatar", modifier = Modifier.size(40.dp))
                            }
                            Text("User ID: ${state.userId}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.expiresAt > 0 && state.expiresAt <= System.currentTimeMillis()) {
                            Text("Authorization expired. Reconnect to sync.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = state.initialized && !state.busy, onClick = {
                            scope.launch {
                                try {
                                    error = ""
                                    val url = owner.authorizationUrl()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("AniList", "Could not start sign-in", e)
                                    error = "Could not open AniList sign-in: ${e.message ?: "unknown error"}"
                                }
                            }
                        }) { Text(if (state.connected) "Reconnect" else "Connect") }
                        TextButton(enabled = state.initialized && !state.busy, onClick = { showDisconnect = true }) {
                            Text(if (state.connected) "Disconnect" else "Clear stored credentials")
                        }
                    }
                }
                AniListCard {
                    Text("Sync options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic sync")
                            Text("Sync on app resume and periodically in the background.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.autoSync, onCheckedChange = owner::setAutoSync,
                            enabled = state.initialized && !state.busy)
                    }
                    Text("AniList statuses are mapped to the corresponding OmniTrack status. Progress only advances.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.message.isNotBlank() || error.isNotBlank()) {
                    AniListCard {
                        Text("Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            if (state.message.isNotBlank()) {
                                Text(
                                    state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (state.hasError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                AniListCard {
                    Text("Library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        label = { Text("Search titles") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Linked", "Unlinked").forEach { option ->
                            TextButton(onClick = { filter = option }) {
                                Text(if (filter == option) "[$option]" else option)
                            }
                        }
                    }
                }
            }
        }
        items(titles, key = { it.id }) { title ->
            val link = state.links[title.mediaId]
            val match = matches[title.mediaId]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsyncImage(model = title.media.coverImage?.large, contentDescription = null,
                            modifier = Modifier.size(width = 48.dp, height = 70.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title.displayTitle, style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Episodes: ${title.progress ?: "Not set"} / ${title.media.episodes ?: "?"} · ${title.status ?: "Unknown"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (link == null) "Not linked" else "Linked: ${entriesById[link.entryId]?.title ?: "Entry unavailable"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (link == null && match != null) {
                                Text("Match: ${match.title}", color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (link == null) {
                            if (match != null) {
                                TextButton(enabled = !state.busy, onClick = {
                                    scope.launch { owner.link(title, match.id) }
                                }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Link match") }
                            }
                            TextButton(enabled = !state.busy,
                                onClick = { error = ""; newTitle = title }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("Add as new")
                            }
                            TextButton(enabled = !state.busy, onClick = { selected = title },
                                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Link existing") }
                        } else {
                            TextButton(enabled = !state.busy, onClick = {
                                scope.launch { owner.unlink(title.mediaId, link.entryId) }
                            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Unlink") }
                        }
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://anilist.co/anime/${title.mediaId}")))
                            } catch (e: Exception) {
                                Log.w("AniList", "Could not open title", e)
                                error = "Could not open the AniList title."
                            }
                        }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("View") }
                    }
                }
            }
        }
        if (state.titles.isEmpty()) {
            item {
                AniListCard {
                    if (state.busy) {
                        Text("Loading your AniList library…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            if (state.connected) "No AniList library loaded. Fetch your library to get started."
                            else "Connect AniList to fetch and link your anime library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (state.titles.isNotEmpty() && titles.isEmpty()) {
            item { AniListCard { Text("No titles match the current search and filter.") } }
        }
        val missingLinks = state.links.filterKeys { id -> state.titles.none { it.mediaId == id } }
        if (missingLinks.isNotEmpty()) {
            item {
                AniListCard {
                    Text("Other linked entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    missingLinks.forEach { (mediaId, link) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${entriesById[link.entryId]?.title ?: "Entry unavailable"} (AniList #$mediaId)",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !state.busy, onClick = {
                                scope.launch { owner.unlink(mediaId, link.entryId) }
                            }) { Text("Unlink") }
                        }
                    }
                }
            }
        }
    }

    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text("Disconnect AniList?") },
            text = { Text("Credentials will be removed. Links remain on this device for this account.") },
            confirmButton = { TextButton(onClick = { showDisconnect = false; owner.disconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { showDisconnect = false }) { Text("Cancel") } }
        )
    }

    batchTitles?.let { selectedTitles ->
        AlertDialog(
            onDismissRequest = { batchTitles = null },
            title = { Text("Batch import as new entries?") },
            text = { Text(
                "Import ${selectedTitles.size} unlinked titles from the current filters. " +
                    "Possible duplicates and titles missing required metadata are reported for review. " +
                    "Cancellation stops after the current title."
            ) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    batchTitles = null
                    owner.batchImport(selectedTitles)
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { batchTitles = null }) { Text("Cancel") } }
        )
    }

    newTitle?.let { title ->
        MediaEntryEditorSheet(
            viewModel = viewModel, existing = null,
            prefill = remember(title) {
                ScrapedMediaDraft(
                    title = title.displayTitle, type = MediaType.ANIME, unitName = "Episode",
                    platformHint = "AniList", sourceUrl = title.sourceUrl,
                    coverUrl = title.media.coverImage?.large,
                    creators = title.media.studios?.nodes.orEmpty().filter { it.name.isNotBlank() }
                        .map { ScrapedCreator(it.name, "studio") },
                    totalChapters = title.media.episodes?.toDouble(),
                    publishingStatus = when (title.media.status) {
                        "FINISHED" -> "completed"
                        "NOT_YET_RELEASED" -> "announced"
                        "CANCELLED" -> "discontinued"
                        "HIATUS" -> "hiatus"
                        else -> "releasing"
                    },
                    userStatus = when (title.status) {
                        "CURRENT", "REPEATING" -> "in_progress"
                        "COMPLETED" -> "completed"
                        "PAUSED" -> "paused"
                        "DROPPED" -> "dropped"
                        else -> "planning"
                    },
                    currentUnit = title.progress?.toDouble()
                )
            },
            entries = entries, saving = saving, onDismiss = { if (!saving) newTitle = null },
            onSave = save@{ form ->
                if (saving) return@save
                if (form.type != MediaType.ANIME ||
                    !(form.unitName.trim().equals("episode", true) || form.unitName.trim().equals("episodes", true)) ||
                    form.mergeIntoEntryId != null) {
                    error = "Create a new anime entry tracking episodes to link AniList."
                    return@save
                }
                saving = true
                scope.launch {
                    var createdId: String? = null
                    try {
                        saveMediaEntry(viewModel, mangaBaka, form, null, entries, null, null) { createdId = it }
                        val id = checkNotNull(createdId) { "The new entry was not created" }
                        owner.link(title, id)
                        if (owner.state.value.links[title.mediaId]?.entryId != id) {
                            error = "Entry created, but linking failed: ${owner.state.value.message}. Link it manually."
                        }
                        newTitle = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("AniList", "Could not create or link entry", e)
                        if (createdId != null || e is PartialMediaEntryCreateException) newTitle = null
                        error = if (createdId != null) {
                            "Entry $createdId was created but linking failed. Link it manually: ${e.message ?: "Unknown error"}"
                        } else e.message ?: "Could not create or link entry."
                    } finally {
                        saving = false
                    }
                }
            }
        )
    }

    if (newTitle != null && error.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { error = "" },
            title = { Text("AniList") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } }
        )
    }

    selected?.let { title ->
        LinkAniListDialog(
            title = title,
            entries = entries,
            linkedIds = state.links.values.map { it.entryId }.toSet(),
            onDismiss = { selected = null },
            onLink = { entryId ->
                selected = null
                scope.launch { owner.link(title, entryId) }
            }
        )
    }
}

@Composable
private fun AniListCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun LinkAniListDialog(
    title: AniListLibraryEntry,
    entries: List<MediaEntry>,
    linkedIds: Set<String>,
    onDismiss: () -> Unit,
    onLink: (String) -> Unit
) {
    var selectedId by remember(title.mediaId) { mutableStateOf<String?>(null) }
    val available = entries.filter {
        it.id !in linkedIds && it.type == MediaType.ANIME &&
            (it.unitName.trim().equals("episode", true) || it.unitName.trim().equals("episodes", true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link existing: ${title.displayTitle}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linking does not change progress. Sync now to import episodes.", style = MaterialTheme.typography.bodySmall)
                if (available.isEmpty()) {
                    Text("No unlinked anime entries that track episodes are available.")
                } else {
                    SearchablePicker(
                        label = "Anime entry",
                        selected = available.firstOrNull { it.id == selectedId }?.title ?: "Choose an entry",
                        title = "Choose anime entry",
                        searchLabel = "Search anime entries",
                        createLabel = "Close picker",
                        options = available,
                        itemKey = { it.id },
                        itemText = { it.title },
                        selectedIds = setOfNotNull(selectedId),
                        onSelected = { selectedId = it.id },
                        onCreate = {},
                        allowCreate = false,
                        dismissOnSelection = false
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selectedId != null, onClick = { selectedId?.let(onLink) }) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
