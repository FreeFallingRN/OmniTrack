package com.omnitrack.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.CreatorRole
import com.omnitrack.app.domain.model.PublishingStatus
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel

internal fun LazyListScope.creatorSection(
    authors: List<AuthorDto>,
    creatorRows: List<CreatorFormRow>,
    onCreatorRowsChange: (List<CreatorFormRow>) -> Unit,
    platforms: List<PlatformDto>,
    platformId: String?,
    onSelectPlatform: (PlatformDto) -> Unit,
    onAddPlatform: () -> Unit,
    createPlatform: Boolean,
    newPlatformName: String,
    onNewPlatformNameChange: (String) -> Unit,
    newPlatformWebsiteUrl: String,
    onNewPlatformWebsiteUrlChange: (String) -> Unit
) {
    item { Text("Creators", style = MaterialTheme.typography.titleMedium) }
    creatorRows.forEachIndexed { index, row ->
        item(key = "creator_row_$index") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Selector(
                            label = "Creator ${index + 1}",
                            selected = when {
                                row.createNew -> "New: ${row.newAuthorName.ifBlank { "Enter name below" }}"
                                else -> authors.firstOrNull { it.id == row.authorId }?.name ?: "Choose creator"
                            },
                            options = authors,
                            text = { it.name },
                            onAdd = {
                                onCreatorRowsChange(creatorRows.toMutableList().also {
                                    it[index] = row.copy(createNew = true, authorId = null)
                                })
                            }
                        ) { selected ->
                            onCreatorRowsChange(creatorRows.toMutableList().also {
                                it[index] = row.copy(authorId = selected.id, createNew = false)
                            })
                        }
                    }
                    if (creatorRows.size > 1) {
                        IconButton(
                            onClick = {
                                onCreatorRowsChange(creatorRows.toMutableList().also { it.removeAt(index) })
                            }
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Remove creator")
                        }
                    }
                }

                if (row.createNew) {
                    OutlinedTextField(
                        value = row.newAuthorName,
                        onValueChange = { updatedName ->
                            onCreatorRowsChange(creatorRows.toMutableList().also {
                                it[index] = row.copy(newAuthorName = updatedName)
                            })
                        },
                        label = { Text("New creator name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                StatusButtonGroup(
                    label = "Role",
                    selected = row.role,
                    options = CreatorRole.values.map { it to it.toDisplayLabel() },
                    compact = true,
                    onSelected = { newRole ->
                        onCreatorRowsChange(creatorRows.toMutableList().also {
                            it[index] = row.copy(role = newRole)
                        })
                    }
                )
            }
        }
    }
    item {
        TextButton(
            onClick = { onCreatorRowsChange(creatorRows + CreatorFormRow(role = "illustrator")) }
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Add another creator / artist")
        }
    }
    item {
        Selector(
            "Platform",
            platforms.firstOrNull { it.id == platformId }?.name ?: "Choose platform",
            platforms,
            { it.name },
            onAdd = onAddPlatform
        ) { onSelectPlatform(it) }
    }
    if (createPlatform) {
        item { HorizontalDivider() }
        item { Text("New platform details", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                newPlatformName,
                onNewPlatformNameChange,
                label = { Text("Platform name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                newPlatformWebsiteUrl,
                onNewPlatformWebsiteUrlChange,
                label = { Text("Website URL (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

internal fun LazyListScope.seriesSection(
    showSeriesDetails: Boolean,
    onToggle: () -> Unit,
    series: List<SeriesDto>,
    seriesId: String?,
    createSeries: Boolean,
    onSelectSeries: (SeriesDto) -> Unit,
    onClearSeries: () -> Unit,
    onAddSeries: () -> Unit,
    newSeriesTitle: String,
    onNewSeriesTitleChange: (String) -> Unit,
    title: String,
    sequence: String,
    onSequenceChange: (String) -> Unit
) {
    item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Series details",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showSeriesDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (showSeriesDetails) "Collapse series details" else "Expand series details",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (showSeriesDetails) {
        item {
            Selector(
                label = "Series (optional)",
                selected = series.firstOrNull { it.id == seriesId }?.title ?: "None (Standalone)",
                options = series,
                text = { it.title },
                onClear = if (seriesId != null || createSeries) onClearSeries else null,
                onAdd = onAddSeries
            ) { onSelectSeries(it) }
        }
        if (createSeries) {
            item {
                OutlinedTextField(
                    newSeriesTitle,
                    onNewSeriesTitleChange,
                    label = { Text("New series name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
        item {
            EntryField(
                label = "Pos. in series (optional)",
                value = sequence,
                onValueChange = onSequenceChange,
                keyboardType = KeyboardType.Decimal
            )
        }
    }
}

internal fun LazyListScope.mediaSection(
    showMediaDetails: Boolean,
    onToggle: () -> Unit,
    userStatus: String,
    onUserStatusChange: (String) -> Unit,
    publishingStatus: String,
    onPublishingStatusChange: (String) -> Unit,
    currentUnit: String,
    onCurrentUnitChange: (String) -> Unit,
    unitName: String,
    onUnitNameChange: (String) -> Unit,
    isCanon: Boolean,
    onIsCanonChange: (Boolean) -> Unit
) {
    item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Media details",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showMediaDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (showMediaDetails) "Collapse media details" else "Expand media details",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (showMediaDetails) {
        item {
            StatusButtonGroup(
                label = "User status",
                selected = userStatus,
                options = UserStatus.entries.map { it.value to it.label },
                onSelected = onUserStatusChange
            )
        }
        item {
            StatusButtonGroup(
                label = "Publishing status",
                selected = publishingStatus,
                options = PublishingStatus.values.map { it to it.toDisplayLabel() },
                onSelected = onPublishingStatusChange
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntryField(
                    label = "Current ${unitName.trim().lowercase()}",
                    value = currentUnit,
                    onValueChange = onCurrentUnitChange,
                    modifier = Modifier.weight(1f)
                )
                EntryField(
                    label = "Unit name",
                    value = unitName,
                    onValueChange = onUnitNameChange,
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Text
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(isCanon, onIsCanonChange)
                Text("Canon entry")
            }
        }
    }
}

internal fun LazyListScope.publicationCoverSection(
    sourceUrl: String,
    onSourceUrlChange: (String) -> Unit,
    findingCover: Boolean,
    onFindCover: () -> Unit,
    coverUrl: String,
    onCoverUrlChange: (String) -> Unit
) {
    item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
    item { Text("Publication and cover", style = MaterialTheme.typography.titleMedium) }
    item {
        OutlinedTextField(
            sourceUrl,
            onSourceUrlChange,
            label = { Text("Publication URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        Button(
            onClick = onFindCover,
            enabled = sourceUrl.isNotBlank() && !findingCover
        ) {
            Text(if (findingCover) "Looking up cover…" else "Find cover from URL")
        }
    }
    if (coverUrl.isNotBlank()) {
        item {
            AsyncImage(
                model = coverUrl,
                contentDescription = "Cover preview",
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
    item {
        OutlinedTextField(
            coverUrl,
            onCoverUrlChange,
            label = { Text("Cover image URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}
