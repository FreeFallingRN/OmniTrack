package com.omnitrack.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MediaDetailSheet(
    entry: MediaEntry,
    relatedEntries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onUpdateProgress: (Double) -> Unit,
    onUpdateStatus: (UserStatus) -> Unit,
    controlsEnabled: Boolean,
    onSelectEntry: (MediaEntry) -> Unit,
    onOpenRelatedEntity: (RelatedEntityDetail) -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit = {}
) {
    var showCover by remember(entry.id) { mutableStateOf(false) }

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
                            IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
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

                        entry.seriesTitle?.let {
                            Text(
                                text = it,
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
                    key(entry.id) {
                        ProgressWheel(
                            entryId = entry.id,
                            currentUnit = entry.currentUnit,
                            unitName = entry.unitName,
                            enabled = controlsEnabled,
                            onUpdateProgress = onUpdateProgress
                        )
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