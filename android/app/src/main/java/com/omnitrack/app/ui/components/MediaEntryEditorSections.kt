package com.omnitrack.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.creatorSection(
    authors: List<AuthorDto>,
    creatorRows: List<CreatorFormRow>,
    onRemoveCreator: (Int) -> Unit,
    creatorDraft: CreatorFormRow,
    onCreatorDraftChange: (CreatorFormRow) -> Unit,
    editingCreator: Int?,
    onEditCreator: (Int) -> Unit,
    onAddCreator: () -> Unit,
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
    item {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp) ) {
                Text("Credits", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    creatorRows.forEachIndexed { index, creator ->
                        val name = if (creator.createNew) creator.newAuthorName
                            else authors.firstOrNull { it.id == creator.authorId }?.name ?: "Unknown creator"
                        InputChip(
                            selected = editingCreator == index,
                            onClick = { onEditCreator(index) },
                            label = { Text("$name · ${creator.role.toDisplayLabel()}") },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveCreator(index) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Remove $name", modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        if (creatorDraft.createNew) {
                            OutlinedTextField(
                                value = creatorDraft.newAuthorName,
                                onValueChange = { onCreatorDraftChange(creatorDraft.copy(newAuthorName = it)) },
                                label = { Text("Creator") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { onCreatorDraftChange(creatorDraft.copy(createNew = false, newAuthorName = "")) }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Choose existing creator")
                                    }
                                }
                            )
                        } else {
                            Selector(
                                label = "Creator",
                                selected = authors.firstOrNull { it.id == creatorDraft.authorId }?.name ?: "Choose",
                                options = authors,
                                text = { it.name },
                                onAdd = { onCreatorDraftChange(creatorDraft.copy(createNew = true, authorId = null)) }
                            ) { onCreatorDraftChange(creatorDraft.copy(authorId = it.id, createNew = false)) }
                        }
                    }
                    Box {
                        var expanded by remember { mutableStateOf(false) }
                        FilterChip(
                            selected = false,
                            onClick = { expanded = true },
                            modifier = Modifier.widthIn(max = 112.dp),
                            label = { Text(creatorDraft.role.toDisplayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            CreatorRole.values.forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.toDisplayLabel()) },
                                    onClick = { onCreatorDraftChange(creatorDraft.copy(role = role)); expanded = false }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onAddCreator,
                        enabled = creatorDraft.authorId != null || (creatorDraft.createNew && creatorDraft.newAuthorName.isNotBlank())
                    ) {
                        Icon(Icons.Default.Add, contentDescription = if (editingCreator == null) "Add creator" else "Update creator")
                    }
                }
                Selector(
                    "Platform",
                    if (createPlatform) "New: ${newPlatformName.ifBlank { "Enter name below" }}"
                    else platforms.firstOrNull { it.id == platformId }?.name ?: "Choose platform",
                    platforms,
                    { it.name },
                    onAdd = onAddPlatform
                ) { onSelectPlatform(it) }
                if (createPlatform) {
                    OutlinedTextField(
                        newPlatformName, onNewPlatformNameChange,
                        label = { Text("Platform name") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        newPlatformWebsiteUrl, onNewPlatformWebsiteUrlChange,
                        label = { Text("Website URL (optional)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.seriesSection(
    showSeriesDetails: Boolean,
    onToggle: () -> Unit,
    series: List<SeriesDto>,
    seriesId: String?,
    seriesTitle: String?,
    createSeries: Boolean,
    onSelectSeries: (SeriesDto) -> Unit,
    onClearSeries: () -> Unit,
    onAddSeries: () -> Unit,
    newSeriesTitle: String,
    onNewSeriesTitleChange: (String) -> Unit,
    sequence: String,
    onSequenceChange: (String) -> Unit,
    positionError: String?
) {
    item {
        val selectedTitle = if (createSeries) newSeriesTitle.ifBlank { "New series" }
            else series.firstOrNull { it.id == seriesId }?.title ?: seriesTitle ?: "Standalone"
        val summary = if ((seriesId != null || createSeries) && sequence.isNotBlank()) {
            "$selectedTitle (#$sequence)"
        } else selectedTitle
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onToggle).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Series · $summary", modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Icon(
                        if (showSeriesDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (showSeriesDetails) "Collapse series details" else "Expand series details"
                    )
                }
                if (showSeriesDetails) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Selector(
                            label = "Series (optional)",
                            selected = if (createSeries) "New: $selectedTitle" else selectedTitle,
                            options = series,
                            text = { it.title },
                            onClear = if (seriesId != null || createSeries) onClearSeries else null,
                            onAdd = onAddSeries
                        ) { onSelectSeries(it) }
                        if (createSeries) {
                            OutlinedTextField(newSeriesTitle, onNewSeriesTitleChange,
                                label = { Text("New series name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        EntryField("Pos. in series (optional)", sequence, onSequenceChange, keyboardType = KeyboardType.Decimal)
                    }
                }
                if (positionError != null) {
                    Text(
                        text = positionError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.mediaSection(
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
    item {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tracking", style = MaterialTheme.typography.titleMedium)
                StatusButtonGroup("User status", userStatus, UserStatus.entries.map { it.value to it.label }, onSelected = onUserStatusChange)
                StatusButtonGroup("Publishing status", publishingStatus,
                    PublishingStatus.values.map { it to it.toDisplayLabel() }, onSelected = onPublishingStatusChange)
                if (publishingStatus.isBlank()) {
                    Text(
                        "Publishing status was not available from the source. Please choose a status.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EntryField("Current ${unitName.trim().lowercase()}", currentUnit, onCurrentUnitChange, Modifier.weight(1f))
                    EntryField("Unit name", unitName, onUnitNameChange, Modifier.weight(1f), KeyboardType.Text)
                }
                FilterChip(selected = isCanon, onClick = { onIsCanonChange(!isCanon) }, label = { Text("Canon") })
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
    item {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Publication and cover", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(width = 72.dp, height = 108.dp)) {
                        if (coverUrl.isNotBlank()) {
                            AsyncImage(model = coverUrl, contentDescription = "Cover preview",
                                modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Text("No cover", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(sourceUrl, onSourceUrlChange, label = { Text("Publication URL") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = onFindCover, enabled = sourceUrl.isNotBlank() && !findingCover) {
                                    if (findingCover) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = "Find cover from URL")
                                    }
                                }
                            })
                        OutlinedTextField(coverUrl, onCoverUrlChange, label = { Text("Cover image URL") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            }
        }
    }
}
