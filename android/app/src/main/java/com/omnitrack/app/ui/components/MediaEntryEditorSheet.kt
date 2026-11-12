package com.omnitrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
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
    val totalUnits: Double?,
    val unitName: String,
    val platform: String?,
    val sourceUrl: String?,
    val coverUrl: String?,
    val isCanon: Boolean
)

data class NewSeriesData(
    val title: String,
    val userStatus: String,
    val publishingStatus: String,
    val unitName: String
)

data class NewAuthorData(
    val name: String
)

private val publishingStatuses = listOf("announced", "releasing", "hiatus", "completed", "discontinued")

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
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            series = SupabaseService.getSeries()
            authors = SupabaseService.getAuthors()
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
    var sequence by remember { mutableStateOf(existing?.sequenceNumber?.toString() ?: "1") }
    var currentUnit by remember { mutableStateOf(existing?.currentUnit?.toString() ?: "0") }
    var totalUnits by remember { mutableStateOf(existing?.totalUnits?.toString().orEmpty()) }
    var unitName by remember { mutableStateOf(existing?.unitName ?: "Chapter") }
    var platform by remember { mutableStateOf(existing?.platform.orEmpty()) }
    var sourceUrl by remember { mutableStateOf(existing?.sourceUrl.orEmpty()) }
    var coverUrl by remember { mutableStateOf(existing?.coverUrl.orEmpty()) }
    var isCanon by remember { mutableStateOf(existing?.isCanon ?: true) }
    var findingCover by remember { mutableStateOf(false) }
    var createSeries by remember { mutableStateOf(false) }
    var createAuthor by remember { mutableStateOf(false) }
    var newSeriesTitle by remember { mutableStateOf("") }
    var newSeriesStatus by remember { mutableStateOf("planning") }
    var newSeriesPublishingStatus by remember { mutableStateOf("releasing") }
    var newSeriesUnitName by remember { mutableStateOf("Chapter") }
    var newAuthorName by remember { mutableStateOf("") }

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
                Selector("Type", type.label, MediaType.entries, { it.label }) { type = it }
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
                item {
                    Selector("Series status", newSeriesStatus, UserStatus.entries, { it.label }) {
                        newSeriesStatus = it.value
                    }
                }
                item {
                    Selector("Series publishing status", newSeriesPublishingStatus, publishingStatuses, { it }) {
                        newSeriesPublishingStatus = it
                    }
                }
                item { EntryField("Series unit name", newSeriesUnitName) { newSeriesUnitName = it } }
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
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { Text("Media details", style = MaterialTheme.typography.titleMedium) }
            item {
                Selector("User status", userStatus, UserStatus.entries, { it.label }) {
                    userStatus = it.value
                }
            }
            item {
                Selector("Publishing status", publishingStatus, publishingStatuses, { it }) {
                    publishingStatus = it
                }
            }
            item { EntryField("Sequence number", sequence) { sequence = it } }
            item { EntryField("Current progress", currentUnit) { currentUnit = it } }
            item { EntryField("Total units", totalUnits) { totalUnits = it } }
            item { EntryField("Unit name", unitName) { unitName = it } }
            item { EntryField("Platform", platform) { platform = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(isCanon, { isCanon = it })
                    Text("Canon entry")
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
                            (selectedAuthor == null && (!createAuthor || newAuthorName.isBlank()))
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
                                            userStatus = newSeriesStatus,
                                            publishingStatus = newSeriesPublishingStatus,
                                            unitName = newSeriesUnitName
                                        )
                                    } else null,
                                    authorId = selectedAuthor,
                                    newAuthor = if (createAuthor) NewAuthorData(newAuthorName) else null,
                                    userStatus = userStatus,
                                    publishingStatus = publishingStatus,
                                    sequenceNumber = sequence.toDoubleOrNull() ?: 1.0,
                                    currentUnit = currentUnit.toDoubleOrNull() ?: 0.0,
                                    totalUnits = totalUnits.toDoubleOrNull(),
                                    unitName = unitName,
                                    platform = platform.ifBlank { null },
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
private fun EntryField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
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
