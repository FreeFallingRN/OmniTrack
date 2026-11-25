package com.omnitrack.app.ui.components

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.utils.findMoonReaderPackage
import com.omnitrack.app.utils.openInMoonReader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MediaDetailSheet(
    entry: MediaEntry,
    relatedEntries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onUpdateProgress: (Double) -> Unit,
    onUpdateStatus: (UserStatus) -> Unit,
    controlsEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") onUnlinkMoonReader: suspend (String) -> Unit,
    checkMoonReaderFolder: suspend (String, Boolean) -> Boolean,
    reportMoonReaderFolderFailure: suspend (String, Boolean) -> Unit,
    onSelectEntry: (MediaEntry) -> Unit,
    onOpenRelatedEntity: (RelatedEntityDetail) -> Unit,
    onEdit: () -> Unit
) {
    var showCover by remember(entry.id) { mutableStateOf(false) }
    var showShare by remember(entry.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE) }
    val linkKey = "moon_reader_file_uri_${entry.id}"
    val moonReaderPackage = remember(context) { findMoonReaderPackage(context) }
    val moonReaderIcon = remember(context, moonReaderPackage) {
        moonReaderPackage?.let { packageName ->
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap() }
                .onFailure { Log.w("MediaDetailSheet", "Unable to load Moon+ Reader icon", it) }
                .getOrNull()
        }
    }
    var moonReaderLinked by remember(prefs, linkKey) { mutableStateOf(prefs.getString(linkKey, null) != null) }
    DisposableEffect(prefs, linkKey) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == linkKey) moonReaderLinked = prefs.getString(linkKey, null) != null
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        moonReaderLinked = prefs.getString(linkKey, null) != null
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
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
                                    val fileUri = prefs.getString(linkKey, null)
                                    val fileName = prefs.getString("moon_reader_file_name_${entry.id}", null)
                                    val folder = prefs.getString("moon_reader_folder", null)
                                    val booksFolder = prefs.getString("moon_reader_books_folder", null)
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
                                        Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { showShare = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
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

                        if (entry.alternativeTitles.isNotEmpty()) {
                            Text(
                                text = "Also known as: ${entry.alternativeTitles.take(3).joinToString(" • ")}",
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
                            DetailStatusPill(entry.userStatus.label)
                            if (!entry.isCanon) {
                                Text(
                                    text = "Non-canon",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
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
                    key(entry.id, moonReaderLinked) {
                        Box(modifier = Modifier.alpha(if (moonReaderLinked) 0.4f else 1f)) {
                            ProgressWheel(
                                entryId = entry.id,
                                currentUnit = entry.currentUnit,
                                unitName = entry.unitName,
                                enabled = controlsEnabled && !moonReaderLinked,
                                onUpdateProgress = { value ->
                                    if (prefs.getString(linkKey, null) == null) onUpdateProgress(value)
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