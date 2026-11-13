package com.omnitrack.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.PublishingStatus
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.utils.CoverLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaEntryEditorData(
    val title: String,
    val type: MediaType,
    val seriesId: String?,
    val newSeries: NewSeriesData?,
    val authorId: String?,
    val newAuthor: NewAuthorData?,
    val userStatus: String,
    val publishingStatus: String,
    val sequenceNumber: Double,
    val currentUnit: Double,
    val unitName: String,
    val platformId: String?,
    val newPlatform: NewPlatformData?,
    val sourceUrl: String?,
    val coverUrl: String?,
    val isCanon: Boolean
)

data class NewSeriesData(
    val title: String,
)

data class NewAuthorData(
    val name: String
)

data class NewPlatformData(
    val name: String,
    val websiteUrl: String?,
    val logoUrl: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaEntryEditorSheet(
    existing: MediaEntry?,
    entries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onSave: (MediaEntryEditorData) -> Unit
) {
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var authors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var platforms by remember { mutableStateOf<List<PlatformDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            series = SupabaseService.getSeries()
            authors = SupabaseService.getAuthors()
            platforms = SupabaseService.getPlatforms()
        } catch (e: Exception) {
            error = e.message ?: "Could not load series and authors"
        }

    }

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: MediaType.NOVEL) }
    var seriesId by remember { mutableStateOf(existing?.seriesId) }
    var authorId by remember(existing?.id, authors) {
        mutableStateOf(authors.firstOrNull { existing?.authors?.contains(it.name) == true }?.id)
    }
    var userStatus by remember { mutableStateOf(existing?.userStatus?.value ?: "planning") }
    var publishingStatus by remember { mutableStateOf(existing?.publishingStatus ?: "releasing") }
    var sequence by remember { mutableStateOf(existing?.sequenceNumber?.toInt()?.toString() ?: "1") }
    var currentUnit by remember { mutableStateOf(existing?.currentUnit?.toString() ?: "0") }
    var unitName by remember { mutableStateOf(existing?.unitName ?: "Chapter") }
    var platformId by remember { mutableStateOf(existing?.platformId) }
    var sourceUrl by remember { mutableStateOf(existing?.sourceUrl.orEmpty()) }
    var coverUrl by remember { mutableStateOf(existing?.coverUrl.orEmpty()) }
    var isCanon by remember { mutableStateOf(existing?.isCanon ?: true) }
    var findingCover by remember { mutableStateOf(false) }
    var createSeries by remember { mutableStateOf(false) }
    var createAuthor by remember { mutableStateOf(false) }
    var newSeriesTitle by remember { mutableStateOf("") }
    var newAuthorName by remember { mutableStateOf("") }
    var createPlatform by remember { mutableStateOf(false) }
    var newPlatformName by remember { mutableStateOf("") }
    var newPlatformWebsiteUrl by remember { mutableStateOf("") }
    var newPlatformLogoUrl by remember { mutableStateOf("") }
    var findingPlatformLogo by remember { mutableStateOf(false) }
    var showMediaDetails by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = if (existing == null) "New entry" else "Edit entry",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            item {
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("Entry title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                StatusButtonGroup(
                    label = "Type",
                    selected = type.value,
                    options = MediaType.entries.map { it.value to it.singularLabel },
                    onSelected = { value ->
                        type = MediaType.fromValue(value)
                    }
                )
            }
            item {
                Selector(
                    "Series",
                    series.firstOrNull { it.id == seriesId }?.title ?: "Choose series",
                    series,
                    { it.title },
                    onAdd = {
                        createSeries = true
                        seriesId = null
                        if (newSeriesTitle.isBlank()) newSeriesTitle = title
                    }
                ) { seriesId = it.id; createSeries = false }
            }
            if (createSeries) {
                item { HorizontalDivider() }
                item { Text("New series details", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(
                        newSeriesTitle,
                        { newSeriesTitle = it },
                        label = { Text("Series name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            item {
                Selector(
                    "Author",
                    authors.firstOrNull { it.id == authorId }?.name ?: "Choose author",
                    authors,
                    { it.name },
                    onAdd = {
                        createAuthor = true
                        authorId = null
                    }
                ) { authorId = it.id; createAuthor = false }
            }
            if (createAuthor) {
                item { HorizontalDivider() }
                item { Text("New author details", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(
                        newAuthorName,
                        { newAuthorName = it },
                        label = { Text("Author name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            item {
                Selector(
                    "Platform",
                    platforms.firstOrNull { it.id == platformId }?.name ?: "Choose platform",
                    platforms,
                    { it.name },
                    onAdd = { createPlatform = true; platformId = null }
                ) { platformId = it.id; createPlatform = false }
            }
            if (createPlatform) {
                item { HorizontalDivider() }
                item { Text("New platform details", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(
                        newPlatformName,
                        { newPlatformName = it },
                        label = { Text("Platform name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        newPlatformWebsiteUrl,
                        { newPlatformWebsiteUrl = it },
                        label = { Text("Website URL (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMediaDetails = !showMediaDetails }
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
                        imageVector = if (showMediaDetails) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
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
                        onSelected = { userStatus = it }
                    )
                }
                item {
                    StatusButtonGroup(
                        label = "Publishing status",
                        selected = publishingStatus,
                        options = PublishingStatus.values.map { it to it.toDisplayLabel() },
                        onSelected = { publishingStatus = it }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EntryField(
                            label = "Pos. in series",
                            value = sequence,
                            onValueChange = { sequence = it },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number
                        )
                        EntryField(
                            label = "Current ${unitName.trim().lowercase()}",
                            value = currentUnit,
                            onValueChange = { currentUnit = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    EntryField(
                        "Unit name",
                        unitName,
                        onValueChange = { unitName = it }
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(isCanon, { isCanon = it })
                        Text("Canon entry")
                    }
                }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { Text("Publication and cover", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    sourceUrl,
                    { sourceUrl = it },
                    label = { Text("Publication URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                findingCover = true
                                val found = CoverLookup.findOpenGraphImage(sourceUrl)
                                withContext(Dispatchers.Main) {
                                    findingCover = false
                                    if (found == null) error = "No cover image found."
                                    else coverUrl = found
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    findingCover = false
                                    error = e.message ?: "Could not find cover image"
                                }
                            }
                        }
                    },
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
                    { coverUrl = it },
                    label = { Text("Cover image URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Button(
                    onClick = {
                        val selectedSeries = seriesId
                        val selectedAuthor = authorId
                        if (title.isBlank() ||
                            (selectedSeries == null && (!createSeries || newSeriesTitle.isBlank())) ||
                            (selectedAuthor == null && (!createAuthor || newAuthorName.isBlank())) ||
                            (platformId == null && (!createPlatform || newPlatformName.isBlank()))
                        ) {
                            error = "Title, series, and author are required."
                        } else {
                            onSave(
                                MediaEntryEditorData(
                                    title = title,
                                    type = type,
                                    seriesId = selectedSeries,
                                    newSeries = if (createSeries) {
                                        NewSeriesData(
                                            title = newSeriesTitle,
                                        )
                                    } else null,
                                    authorId = selectedAuthor,
                                    newAuthor = if (createAuthor) NewAuthorData(newAuthorName) else null,
                                    platformId = platformId,
                                    newPlatform = if (createPlatform) {
                                        NewPlatformData(
                                            name = newPlatformName,
                                            websiteUrl = newPlatformWebsiteUrl.ifBlank { null },
                                            logoUrl = newPlatformLogoUrl.ifBlank { null }
                                        )
                                    } else null,
                                    userStatus = userStatus,
                                    publishingStatus = publishingStatus,
                                    sequenceNumber = sequence.toDoubleOrNull() ?: 1.0,
                                    currentUnit = currentUnit.toDoubleOrNull() ?: 0.0,
                                    unitName = unitName,
                                    sourceUrl = sourceUrl.ifBlank { null },
                                    coverUrl = coverUrl.ifBlank { null },
                                    isCanon = isCanon
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save entry")
                }
            }
        }
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Entry editor") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun EntryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true
    )
}

@Composable
private fun StatusButtonGroup(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(text) }
                )
            }
        }
    }
}

@Composable
private fun <T> Selector(
    label: String,
    selected: String,
    options: List<T>,
    text: (T) -> String,
    onAdd: (() -> Unit)? = null,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    onClick = { expanded = false; onSelected(option) }
                )
            }
            if (onAdd != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Create new $label") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { expanded = false; onAdd() }
                )
            }
        }
    }
}
