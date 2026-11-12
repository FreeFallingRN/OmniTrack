package com.omnitrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.MediaEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(
    entry: MediaEntry,
    onDismiss: () -> Unit,
    onUpdateProgress: (Double) -> Unit,
    onToggleActiveTarget: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    // FIX: Initialize local state once per sheet opening using standard mutableStateOf.
    // This prevents Compose from wiping out your active edits during rapid button tapping.
    var progressText by remember {
        mutableStateOf(entry.currentUnit.toString())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Cover Avatar + Title & Metadata
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
                    val initials = entry.title.split(" ")
                        .take(2)
                        .mapNotNull { it.firstOrNull()?.uppercase() }
                        .joinToString("")
                    Text(
                        text = if (initials.isNotEmpty()) initials else "TR",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(entry.type.label)
                            entry.seriesTitle?.let { append(" • $it") }
                            append(" • ${entry.userStatus.label}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Database Info Grid / Details section
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tracking Details",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DetailRow(label = "Current Progress", value = "${entry.unitName} $progressText")
                        DetailRow(label = "Active Target", value = if (entry.isActiveTarget) "Yes (Pinned Notification)" else "No")
                        DetailRow(label = "Media ID", value = entry.id)
                    }
                }
            }

            // Clean Inline Progress Updater
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Update Progress",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = progressText,
                        onValueChange = { newValue ->
                            progressText = newValue
                            newValue.toDoubleOrNull()?.let { onUpdateProgress(it) }
                        },
                        label = { Text("Chapter") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    // Quick decrement button (-1)
                    FilledTonalIconButton(
                        onClick = {
                            val currentVal = progressText.toDoubleOrNull() ?: entry.currentUnit
                            val next = (currentVal - 1.0).coerceAtLeast(0.0)
                            progressText = next.toString()
                            onUpdateProgress(next)
                        }
                    ) {
                        Text("-1", fontWeight = FontWeight.Bold)
                    }

                    // Quick increment button (+1)
                    FilledIconButton(
                        onClick = {
                            val currentVal = progressText.toDoubleOrNull() ?: entry.currentUnit
                            val next = currentVal + 1.0
                            progressText = next.toString()
                            onUpdateProgress(next)
                        }
                    ) {
                        Text("+1", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Pin Persistent Notification Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pin Persistent Notification",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = entry.isActiveTarget,
                    onCheckedChange = { onToggleActiveTrust -> onToggleActiveTarget(onToggleActiveTrust) }
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}