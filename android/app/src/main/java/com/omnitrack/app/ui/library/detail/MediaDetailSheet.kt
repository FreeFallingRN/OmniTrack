package com.omnitrack.app.ui.library.detail

import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.feature.mangabaka.matching.mangaBakaMatchesType
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.components.formatUnit
import com.omnitrack.app.ui.library.editor.StatusButtonGroup
import com.omnitrack.app.ui.share.MediaShareDialog
import com.omnitrack.app.utils.findMoonReaderPackage
import com.omnitrack.app.utils.openInMoonReader
import com.omnitrack.app.utils.MoonReaderMatch
import com.omnitrack.app.utils.MoonReaderProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MediaDetailSheet(
    entry: MediaEntry,
    mangaBakaLinked: Boolean,
    relatedEntries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onUpdateProgress: (Double) -> Unit,
    onUpdateStatus: (UserStatus) -> Unit,
    controlsEnabled: Boolean,
    onUnlinkMoonReader: suspend (String) -> Unit,
    checkMoonReaderFolder: suspend (String, Boolean) -> Boolean,
    reportMoonReaderFolderFailure: suspend (String, Boolean) -> Unit,
    onSelectEntry: (MediaEntry) -> Unit,
    onOpenRelatedEntity: (RelatedEntityDetail) -> Unit,
    onEdit: () -> Unit
) {
    var showCover by remember(entry.id) { mutableStateOf(false) }
    var showShare by remember(entry.id) { mutableStateOf(false) }
    var menuExpanded by remember(entry.id) { mutableStateOf(false) }
    var linkSource by remember(entry.id) { mutableStateOf<String?>(null) }
    var showUnlink by remember(entry.id) { mutableStateOf(false) }
    var unlinkBusy by remember(entry.id) { mutableStateOf(false) }
    var unlinkError by remember(entry.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val hapticFeedback = LocalHapticFeedback.current
    val mangaBakaProgressLocked = mangaBakaLinked && entry.type == MediaType.COMIC
    val settings = LocalFeatureServices.current.settings
    val mangaBaka = LocalFeatureServices.current.mangaBaka.mangaBaka
    val mangaBakaState by mangaBaka.state.collectAsState()
    val mangaBakaLink = mangaBakaState.links.entries.firstOrNull { it.value.entryId == entry.id }
    val settingsState by settings.state.collectAsState()
    val moonReaderLinked = remember(entry.id, settingsState.revision) {
        settings.linkedFileUri(entry.id) != null
    }
    val linked = mangaBakaLink != null || moonReaderLinked
    val moonReaderPackage = remember(context) { findMoonReaderPackage(context) }
    val moonReaderIcon = remember(context, moonReaderPackage) {
        moonReaderPackage?.let { packageName ->
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap() }
                .onFailure { Log.w("MediaDetailSheet", "Unable to load Moon+ Reader icon", it) }
                .getOrNull()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (entry.coverUrl.isNullOrBlank()) {
                            Text(
                                text = entry.title.split(" ").take(2)
                                    .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
                                    .ifEmpty { "TR" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            AsyncImage(
                                model = entry.coverUrl,
                                contentDescription = "${entry.title} cover",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { showCover = true }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!entry.sourceUrl.isNullOrBlank()) {
                            PlatformLinkButton(url = entry.sourceUrl, platform = entry.platform)
                        }
                        if (moonReaderLinked && moonReaderPackage != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(28.dp).clickable {
                                    val fileUri = settings.linkedFileUri(entry.id)
                                    val fileName = settings.linkedFileName(entry.id)
                                    val folder = settings.state.value.progressFolder
                                    val booksFolder = settings.state.value.booksFolder
                                    scope.launch {
                                        openInMoonReader(
                                            context, moonReaderPackage, fileUri, fileName, folder, booksFolder,
                                            checkMoonReaderFolder, reportMoonReaderFolderFailure
                                        )
                                    }
                                }.semantics { contentDescription = "Open in Moon+ Reader" }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (moonReaderIcon != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = moonReaderIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = entry.title,
                                modifier = Modifier.weight(1f).combinedClickable(
                                    hapticFeedbackEnabled = false,
                                    onClick = {},
                                    onLongClick = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText("Title", entry.title))
                                            )
                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Box {
                                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = Color.Black
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Edit", color = Color.White) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = Color.White) },
                                        onClick = { menuExpanded = false; onEdit() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share", color = Color.White) },
                                        leadingIcon = { Icon(Icons.Default.Share, null, tint = Color.White) },
                                        onClick = { menuExpanded = false; showShare = true }
                                    )
                                    if (linked) {
                                        if (moonReaderLinked) {
                                            DropdownMenuItem(
                                                text = { Text("Linked: Moon+", color = Color.White) },
                                                onClick = {},
                                                enabled = false
                                            )
                                        }
                                        if (mangaBakaLink != null) {
                                            DropdownMenuItem(
                                                text = { Text("Linked: MangaBaka", color = Color.White) },
                                                onClick = {},
                                                enabled = false
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Unlink", color = Color.White) },
                                            leadingIcon = { Icon(Icons.Default.LinkOff, null, tint = Color.White) },
                                            onClick = { menuExpanded = false; unlinkError = null; showUnlink = true }
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Link", color = Color.White) },
                                            leadingIcon = { Icon(Icons.Default.Link, null, tint = Color.White) },
                                            onClick = { menuExpanded = false; linkSource = "choose" }
                                        )
                                    }
                                }
                            }
                        }

                        val displayCreators = entry.creators.ifEmpty {
                            entry.authors.map { EntryCreator(name = it, role = "author") }
                        }
                        if (displayCreators.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                displayCreators.forEach { creator ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${creator.role.toDisplayLabel()}: ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = creator.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable {
                                                onOpenRelatedEntity(
                                                    RelatedEntityDetail(
                                                        creator.name,
                                                        creator.role.toDisplayLabel()
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (entry.allAlternativeTitles.isNotEmpty()) {
                            Text(
                                text = "Also known as: ${entry.allAlternativeTitles.take(3).joinToString(" • ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        entry.seriesTitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it + (entry.sequenceNumber?.let { position ->
                                    " #${formatUnit(position)}"
                                } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    onOpenRelatedEntity(
                                        RelatedEntityDetail(
                                            title = it,
                                            type = "Series",
                                            works = relatedEntries + entry,
                                            imageUrl = entry.coverUrl,
                                            userStatus = entry.userStatus.label,
                                            publishingStatus = entry.publishingStatus
                                        )
                                    )
                                }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DetailStatusPill(entry.publishingStatus.toDisplayLabel())
                            if (!entry.isCanon) {
                                Text(
                                    text = "Non-canon",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        if (mangaBakaLink != null || moonReaderLinked) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (mangaBakaLink != null) DetailStatusPill("Linked: MangaBaka")
                                if (moonReaderLinked) DetailStatusPill("Linked: Moon+")
                            }
                        }
                    }
                }
            }

            item {
                StatusButtonGroup(
                    label = "User status",
                    selected = entry.userStatus.value,
                    options = UserStatus.entries.map { it.value to it.label },
                    enabled = controlsEnabled,
                    onSelected = { onUpdateStatus(UserStatus.fromValue(it)) }
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Update progress",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (mangaBakaProgressLocked) {
                        Text("Synced via MangaBaka", style = MaterialTheme.typography.labelMedium)
                    }
                    key(entry.id, moonReaderLinked, mangaBakaProgressLocked) {
                        Box(modifier = Modifier.alpha(if (moonReaderLinked || mangaBakaProgressLocked) 0.4f else 1f)) {
                            ProgressWheel(
                                entryId = entry.id,
                                currentUnit = entry.currentUnit,
                                unitName = entry.unitName,
                                enabled = controlsEnabled && !moonReaderLinked && !mangaBakaProgressLocked,
                                onUpdateProgress = { value ->
                                    if (!mangaBakaProgressLocked && settings.linkedFileUri(entry.id) == null) onUpdateProgress(value)
                                }
                            )
                        }
                    }
                }
            }

            if (relatedEntries.isNotEmpty()) {
                item {
                    Text(
                        text = "Other works in ${entry.seriesTitle ?: "this series"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(relatedEntries, key = { it.id }) { relatedEntry ->
                    MediaWorkRow(
                        work = relatedEntry,
                        onClick = { onSelectEntry(relatedEntry) }
                    )
                }
            }
        }
    }

    if (showShare) {
        MediaShareDialog(entry = entry, onDismiss = { showShare = false })
    }

    if (linkSource == "choose") {
        AlertDialog(
            onDismissRequest = { linkSource = null },
            title = { Text("Link ${entry.title}") },
            text = {
                Column {
                    val sources = if (entry.type == MediaType.NOVEL) {
                        listOf("Moon+ Reader", "MangaBaka")
                    } else listOf("MangaBaka", "Moon+ Reader")
                    sources.forEach { source ->
                        TextButton(onClick = { linkSource = source }) { Text(source) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linkSource = null }) { Text("Cancel") } }
        )
    }
    if (linkSource == "MangaBaka") {
        DetailMangaBakaLinkDialog(entry, onDismiss = { linkSource = null })
    }
    if (linkSource == "Moon+ Reader") {
        DetailMoonReaderLinkDialog(entry, onDismiss = { linkSource = null })
    }
    if (showUnlink) {
        AlertDialog(
            onDismissRequest = { if (!unlinkBusy) showUnlink = false },
            title = { Text("Unlink ${entry.title}") },
            text = {
                Column {
                    Text("Choose a link to remove.")
                    unlinkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (mangaBakaLink != null) {
                        TextButton(enabled = !unlinkBusy && !mangaBakaState.busy, onClick = {
                            scope.launch {
                                unlinkBusy = true
                                try {
                                    mangaBaka.unlinkEntry(mangaBakaLink.key, entry.id)
                                    if (mangaBaka.state.value.links[mangaBakaLink.key]?.entryId != entry.id) {
                                        showUnlink = false
                                    } else unlinkError = mangaBaka.state.value.message
                                } finally {
                                    unlinkBusy = false
                                }
                            }
                        }) { Text("Unlink MangaBaka") }
                    }
                    if (moonReaderLinked) {
                        TextButton(enabled = !unlinkBusy, onClick = {
                            scope.launch {
                                unlinkBusy = true
                                try {
                                    onUnlinkMoonReader(entry.id)
                                    showUnlink = false
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("MediaDetailSheet", "Could not unlink Moon+ Reader", e)
                                    unlinkError = "Could not unlink Moon+ Reader: ${e.message ?: "Unknown error"}"
                                } finally {
                                    unlinkBusy = false
                                }
                            }
                        }) { Text("Unlink Moon+ Reader") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !unlinkBusy, onClick = { showUnlink = false }) { Text("Cancel") }
            }
        )
    }

    if (showCover && !entry.coverUrl.isNullOrBlank()) {
        Dialog(
            onDismissRequest = { showCover = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showCover = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = entry.coverUrl,
                    contentDescription = "${entry.title} cover enlarged",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailMangaBakaLinkDialog(entry: MediaEntry, onDismiss: () -> Unit) {
    val mangaBaka = LocalFeatureServices.current.mangaBaka.mangaBaka
    val state by mangaBaka.state.collectAsState()
    val scope = rememberCoroutineScope()
    var search by remember(entry.id) { mutableStateOf(entry.title) }
    var selected by remember(entry.id) { mutableStateOf<MangaBakaTitle?>(null) }
    var offsetText by remember(entry.id) { mutableStateOf("0") }
    var submitting by remember(entry.id) { mutableStateOf(false) }
    val offset = offsetText.toDoubleOrNull()?.takeIf { it.isFinite() }
    val alreadyLinked = state.links.values.any { it.entryId == entry.id }
    val chapterUnit = entry.unitName.trim().equals("chapter", true) ||
        entry.unitName.trim().equals("chapters", true)
    val available = state.titles.filter {
        it.title.contains(search, ignoreCase = true) &&
            mangaBakaMatchesType(it, entry.type) && !state.links.containsKey(it.series.id)
    }

    LaunchedEffect(state.initialized, state.connected) {
        if (state.initialized && state.connected) mangaBaka.fetch()
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Link MangaBaka") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!state.connected) Text("Connect MangaBaka in Settings first.")
                if (alreadyLinked) Text("This entry is already linked to MangaBaka.")
                if (!chapterUnit) Text("MangaBaka links require chapter-based entries.")
                if (entry.type != MediaType.NOVEL && entry.type != MediaType.COMIC) {
                    Text("MangaBaka links require a novel or comic.")
                }
                if (state.busy) Text("Working…")
                if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    search, { search = it }, label = { Text("Search MangaBaka library") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    offsetText, { offsetText = it }, label = { Text("Chapter offset") },
                    isError = offset == null, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Comic progress uses this offset. Novel progress is not synced from MangaBaka.",
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(available, key = { it.series.id }) { title ->
                        TextButton(
                            enabled = !submitting,
                            onClick = { selected = title },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (selected?.series?.id == title.series.id) "[${title.title}]" else title.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.connected && !state.busy && !submitting && !alreadyLinked &&
                    chapterUnit && selected != null && offset != null &&
                    available.any { it.series.id == selected?.series?.id },
                onClick = {
                    val title = selected
                    if (title != null && offset != null) {
                        scope.launch {
                            submitting = true
                            try {
                                mangaBaka.link(title, entry.id, offset)
                                if (mangaBaka.state.value.links[title.series.id]?.entryId == entry.id) onDismiss()
                            } finally {
                                submitting = false
                            }
                        }
                    }
                }
            ) { Text("Link") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DetailMoonReaderLinkDialog(entry: MediaEntry, onDismiss: () -> Unit) {
    val services = LocalFeatureServices.current
    val moonReader = services.moonReader
    val settingsState by services.settings.state.collectAsState()
    val mangaBakaState by services.mangaBaka.mangaBaka.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snapshot = remember(entry.id) { entry }
    val folder = settingsState.progressFolder
    var search by remember(entry.id) { mutableStateOf("") }
    var files by remember(entry.id) { mutableStateOf<List<MoonReaderProgress>>(emptyList()) }
    var selected by remember(entry.id) { mutableStateOf<MoonReaderProgress?>(null) }
    var chapterText by remember(entry.id) { mutableStateOf(entry.currentUnit.toString()) }
    var busy by remember(entry.id) { mutableStateOf(false) }
    var applying by remember(entry.id) { mutableStateOf(false) }
    var message by remember(entry.id) { mutableStateOf<String?>(null) }
    var scannedFolder by remember(entry.id) { mutableStateOf<String?>(null) }
    val chapter = chapterText.toDoubleOrNull()?.takeIf {
        it.isFinite() && it in 0.0..99999.99 &&
            java.math.BigDecimal.valueOf(it).stripTrailingZeros().scale() <= 2
    }
    val linked = services.settings.linkedFileUri(entry.id) != null
    val supported = (entry.type == MediaType.NOVEL || entry.type == MediaType.COMIC) &&
        moonReader.isChapterUnit(entry.unitName)
    val mangaControlled = entry.type == MediaType.COMIC &&
        mangaBakaState.links.values.any { it.entryId == entry.id }

    LaunchedEffect(folder) {
        files = emptyList()
        selected = null
        scannedFolder = null
        message = null
        if (folder == null) {
            message = "Choose a Moon+ Reader progress folder in Settings first."
        } else {
            busy = true
            try {
                val result = moonReader.scan(Uri.parse(folder), emptyList())
                if (result == null) {
                    message = "Moon+ Reader folder access expired or the scan timed out. Check Settings."
                } else {
                    files = result.first.unmatched.filter {
                        it.fileUri != null && it.fileName?.endsWith(".po", true) == true
                    }.sortedBy { it.fileName?.lowercase() }
                    scannedFolder = folder
                    if (result.first.errors > 0) message = result.first.message
                    else if (files.isEmpty()) message = "No readable .po files found."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                moonReader.setMoonReaderFolderAccess(folder, books = false, valid = false)
                message = "Moon+ Reader folder access expired. Check Settings."
            } catch (e: Exception) {
                Log.e("MediaDetailSheet", "Moon+ Reader scan failed", e)
                message = "Moon+ Reader scan failed: ${e.message ?: "Unknown error"}"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text("Link Moon+ Reader") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (linked) Text("This entry is already linked to Moon+ Reader.")
                if (!supported) Text("Moon+ Reader links require a novel or comic that tracks chapters.")
                if (mangaControlled) Text("Unlink MangaBaka before linking this comic to Moon+ Reader.")
                if (busy) Text(if (applying) "Linking…" else "Scanning…")
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    search, { search = it }, label = { Text("Search files or titles") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    chapterText, { chapterText = it }, label = { Text("Current chapter") },
                    isError = chapter == null, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("Set the current chapter to calibrate future sync.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(
                        files.filter { it.title.contains(search, true) || it.fileName.orEmpty().contains(search, true) },
                        key = { it.fileUri.toString() }
                    ) { progress ->
                        TextButton(
                            enabled = !busy,
                            onClick = { selected = progress },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(if (selected == progress) "[${progress.fileName}]" else progress.fileName.orEmpty())
                                Text("Title: ${progress.title}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Chapter index: ${progress.spineChapterIndex} · ${progress.percent}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !linked && supported && !mangaControlled &&
                    selected != null && chapter != null && scannedFolder == folder && folder != null,
                onClick = {
                    val progress = selected
                    val previewFolder = scannedFolder
                    if (progress != null && chapter != null && previewFolder != null) {
                        scope.launch {
                            busy = true
                            applying = true
                            try {
                                moonReader.importMoonReaderProgress(MoonReaderMatch(snapshot, progress), chapter, previewFolder)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("MediaDetailSheet", "Could not link Moon+ Reader", e)
                                message = "Could not link ${progress.fileName}: ${e.message ?: "Unknown error"}"
                            } finally {
                                busy = false
                                applying = false
                            }
                        }
                    }
                }
            ) { Text("Link") }
        },
        dismissButton = { TextButton(enabled = !applying, onClick = onDismiss) { Text("Cancel") } }
    )
}

private val knownPlatformMonograms = listOf(
    "amazon" to "A",
    "mangadex" to "MD",
    "myanimelist" to "MAL",
    "anilist" to "AL",
    "royalroad" to "RR",
    "webnovel" to "W",
    "novelpia" to "N"
)

@Composable
private fun PlatformLinkButton(url: String, platform: String?) {
    val context = LocalContext.current
    val link = remember(url) {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
        else "https://$trimmed"
    }
    val host = remember(link) { runCatching { Uri.parse(link).host }.getOrNull().orEmpty() }
    val monogram = remember(host, platform) {
        val key = "${platform.orEmpty()} $host".lowercase().replace(Regex("[^a-z]"), "")
        knownPlatformMonograms.firstOrNull { key.contains(it.first) }?.second
    }
    val fallback: @Composable () -> Unit = {
        if (monogram != null) {
            Text(
                text = monogram,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        } else {
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .size(28.dp)
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics {
            contentDescription = "Open ${platform?.takeIf { it.isNotBlank() } ?: host.ifBlank { "link" }} in browser"
        }) {
            if (host.isBlank()) {
                fallback()
            } else {
                SubcomposeAsyncImage(
                    model = "https://www.google.com/s2/favicons?domain=$host&sz=64",
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
                    loading = { fallback() },
                    error = { fallback() }
                )
            }
        }
    }
}

@Composable
private fun DetailStatusPill(status: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}