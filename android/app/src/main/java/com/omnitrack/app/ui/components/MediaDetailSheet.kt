package com.omnitrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.MediaEntry
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(
    entry: MediaEntry,
    relatedEntries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onUpdateProgress: (Double) -> Unit,
    onSelectEntry: (MediaEntry) -> Unit,
    onOpenRelatedEntity: (RelatedEntityDetail) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var progressText by remember(entry.id) {
        mutableStateOf(entry.currentUnit.toString())
    }
    var showCover by remember(entry.id) { mutableStateOf(false) }
    var showDeleteWarning by remember(entry.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 36.dp),
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
                            .size(64.dp)
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
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { showCover = true }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${entry.type.singularLabel} by",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        entry.authors.forEach { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    onOpenRelatedEntity(RelatedEntityDetail(author, "Author"))
                                }
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
                                            works = relatedEntries + entry
                                        )
                                    )
                                }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(entry.userStatus.label)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Update progress",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = progressText,
                            onValueChange = { value ->
                                progressText = value
                                value.toDoubleOrNull()?.let(onUpdateProgress)
                            },
                            label = { Text(entry.unitName) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val next = (progressText.toDoubleOrNull() ?: entry.currentUnit) + 1.0
                                progressText = next.toString()
                                onUpdateProgress(next)
                            }
                        ) {
                            Text("+1")
                        }
                        FilledTonalButton(
                            onClick = {
                                val next = (progressText.toDoubleOrNull() ?: entry.currentUnit) + 10.0
                                progressText = next.toString()
                                onUpdateProgress(next)
                            }
                        ) {
                            Text("+10")
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

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Text("Edit")
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showDeleteWarning = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete")
                    }
                }
            }
        }

            if (showDeleteWarning) {
                AlertDialog(
                    onDismissRequest = { showDeleteWarning = false },
                    title = { Text("Delete entry permanently?") },
                    text = { Text("This removes the entry, its author links, progress, and history.") },
                    confirmButton = {
                        DeleteCountdownButton {
                            showDeleteWarning = false
                            onDelete()
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showDeleteWarning = false }) {
                            Text("Cancel")
                        }
                    }
                )
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
                    modifier = Modifier.fillMaxWidth().padding(20.dp)
                )
            }
        }

    }
}

@Composable
private fun StatusPill(status: String) {
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
