package com.omnitrack.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.SeriesViewModel
import com.omnitrack.app.utils.CoverLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val publishingStatuses = listOf("announced", "releasing", "hiatus", "completed", "discontinued")

private data class SeriesFormData(
    val title: String,
    val rating: Int?,
    val notes: String?
)

private val seriesStatusPriority = listOf(
    UserStatus.IN_PROGRESS,
    UserStatus.DROPPED,
    UserStatus.PAUSED,
    UserStatus.PLANNING,
    UserStatus.COMPLETED
)

private val seriesPublishingStatusPriority =
    listOf("discontinued", "hiatus", "releasing", "announced", "completed")

private fun derivedSeriesStatus(entries: List<MediaEntry>, seriesId: String): String {
    return entries
        .asSequence()
        .filter { it.seriesId == seriesId }
        .map { it.userStatus }
        .minByOrNull { seriesStatusPriority.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        ?.value
        ?: UserStatus.PLANNING.value
}

private fun derivedSeriesPublishingStatus(entries: List<MediaEntry>, seriesId: String): String {
    return entries
        .asSequence()
        .filter { it.seriesId == seriesId }
        .map { it.publishingStatus }
        .minByOrNull {
            seriesPublishingStatusPriority.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }
        ?: "releasing"
}

private data class AuthorFormData(
    val name: String,
    val nativeName: String?,
    val imageUrl: String?,
    val bio: String?
)

private data class EntryFormData(
    val title: String,
    val type: MediaType,
    val seriesId: String?,
    val newSeriesName: String,
    val newSeriesUserStatus: String,
    val newSeriesPublishingStatus: String,
    val newSeriesCurrent: Double?,
    val newSeriesTotal: Double?,
    val newSeriesUnitName: String,
    val newSeriesRating: Int?,
    val newSeriesNotes: String?,
    val authorId: String?,
    val newAuthor: AuthorFormData?,
    val coverUrl: String,
    val sourceUrl: String,
    val platform: String,
    val userStatus: String,
    val publishingStatus: String,
    val sequenceNumber: Double,
    val currentUnit: Double,
    val totalUnits: Double?,
    val unitName: String,
    val isCanon: Boolean
)

@Composable
fun LibraryManagementScreen(viewModel: SeriesViewModel) {
    val entries by viewModel.mediaEntries.collectAsState()
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var authors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var selectedSection by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                series = SupabaseService.getSeries()
                authors = SupabaseService.getAuthors()
                viewModel.refreshData()
            } catch (e: Exception) {
                error = e.message ?: "Could not load library management data"
            }
        }
    }

    LaunchedEffect(reloadToken) { reload() }

    var showSeriesEditor by remember { mutableStateOf(false) }
    var showAuthorEditor by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<SeriesDto?>(null) }
    var selectedAuthor by remember { mutableStateOf<AuthorDto?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedKind by remember { mutableStateOf<String?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectedId = null
                    when (selectedSection) {
                        0 -> { selectedSeries = null; showSeriesEditor = true }
                        else -> { selectedAuthor = null; showAuthorEditor = true }
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Manage library", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Create and maintain series and authors.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf("Serie", "Author").forEachIndexed { index, label ->
                if (selectedSection == index) {
                    FilledTonalButton(onClick = { selectedSection = index }) { Text(label) }
                } else {
                    TextButton(onClick = {
                        selectedSection = index
                        selectedId = null
                        selectedKind = null
                    }) { Text(label) }
                }
            }
            }

            if (selectedId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Selected for deletion",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = { showDeleteWarning = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                    }
                }
            }
            HorizontalDivider()

            when (selectedSection) {
            0 -> {
                series.forEach { item ->
                    ManagementRow(
                        title = item.title,
                        subtitle = "${entries.count { it.seriesId == item.id }} book(s)",
                        selected = selectedId == item.id,
                        onClick = { selectedSeries = item; showSeriesEditor = true },
                        onLongClick = { selectedId = item.id; selectedKind = "series" }
                    )
                }
            }
            else -> {
                authors.forEach { author ->
                    ManagementRow(
                        title = author.name,
                        subtitle = "Author",
                        selected = selectedId == author.id,
                        onClick = { selectedAuthor = author; showAuthorEditor = true },
                        onLongClick = { selectedId = author.id; selectedKind = "author" }
                    )
                }
            }
            }
        }
    }

    if (showDeleteWarning && selectedId != null) {
        val selectedSeriesItem = series.firstOrNull { it.id == selectedId }
        val selectedAuthorItem = authors.firstOrNull { it.id == selectedId }
        val warning = when (selectedKind) {
            "series" -> "This permanently deletes ${selectedSeriesItem?.title ?: "the series"}, every entry in it, their progress, and history."
            else -> "This permanently deletes ${selectedAuthorItem?.name ?: "the author"} and removes the author link from every entry."
        }
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            title = { Text("Permanent deletion") },
            text = { Text(warning) },
            confirmButton = {
                HoldToDeleteButton {
                    scope.launch {
                        try {
                            when (selectedKind) {
                                "series" -> SupabaseService.deleteSeries(selectedId!!)
                                "author" -> SupabaseService.deleteAuthor(selectedId!!)
                            }
                            showDeleteWarning = false
                            selectedId = null
                            selectedKind = null
                            reloadToken++
                        } catch (e: Exception) {
                            error = e.message ?: "Could not delete selected item"
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteWarning = false }) { Text("Cancel") } }
        )
    }

    if (showSeriesEditor) {
        SeriesEditorDialog(
            existing = selectedSeries,
            derivedUserStatus = selectedSeries?.let { derivedSeriesStatus(entries, it.id) }
                ?: UserStatus.PLANNING.value,
            derivedPublishingStatus = selectedSeries?.let {
                derivedSeriesPublishingStatus(entries, it.id)
            } ?: "releasing",
            onDismiss = { showSeriesEditor = false },
            onSave = { form ->
                scope.launch {
                    try {
                        if (selectedSeries == null) {
                            SupabaseService.createSeries(
                                CreateSeriesRequest(
                                    title = form.title,
                                    rating = form.rating,
                                    notes = form.notes
                                )
                            )
                        } else {
                            SupabaseService.updateSeries(
                                selectedSeries!!.id,
                                form.title,
                                form.rating,
                                form.notes
                            )
                        }
                        showSeriesEditor = false
                        reloadToken++
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save series"
                    }
                }
            }
        )
    }

    if (showAuthorEditor) {
        AuthorEditorDialog(
            existing = selectedAuthor,
            onDismiss = { showAuthorEditor = false },
            onSave = { form ->
                scope.launch {
                    try {
                        if (selectedAuthor == null) {
                            SupabaseService.createAuthor(form.name, form.nativeName, form.imageUrl, form.bio)
                        } else {
                            SupabaseService.updateAuthor(
                                selectedAuthor!!.id,
                                form.name,
                                form.nativeName,
                                form.imageUrl,
                                form.bio
                            )
                        }
                        showAuthorEditor = false
                        reloadToken++
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save author"
                    }
                }
            }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Management error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun ManagementRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HoldToDeleteButton(onConfirmed: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        if (held) {
            kotlinx.coroutines.delay(2000)
            held = false
            onConfirmed()
        }
    }
    Button(
        onClick = {},
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                held = true
                val event = awaitPointerEvent()
                if (event.changes.any { it.changedToUp() }) held = false
                down.consume()
            }
        }
    ) {
        Text(if (held) "Keep holding…" else "Hold 2 seconds")
    }
}

@Composable
private fun SeriesEditorDialog(
    existing: SeriesDto?,
    derivedUserStatus: String,
    derivedPublishingStatus: String,
    onDismiss: () -> Unit,
    onSave: (SeriesFormData) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var rating by remember { mutableStateOf(existing?.rating?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }

    EditorDialog(
        title = if (existing == null) "Add series" else "Edit series",
        onDismiss = onDismiss,
        onSave = {
            if (title.isNotBlank()) {
                onSave(
                    SeriesFormData(
                        title, rating.toIntOrNull(), notes.ifBlank { null }
                    )
                )
            }
        }
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Series name") }, singleLine = true)
        OutlinedTextField(
            value = UserStatus.fromValue(derivedUserStatus).label,
            onValueChange = {},
            label = { Text("User status") },
            readOnly = true,
            singleLine = true
        )
        OutlinedTextField(
            value = derivedPublishingStatus,
            onValueChange = {},
            label = { Text("Publishing status") },
            readOnly = true,
            singleLine = true
        )
        OutlinedTextField(rating, { rating = it }, label = { Text("Rating") }, singleLine = true)
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2)
    }
}

@Composable
private fun AuthorEditorDialog(
    existing: AuthorDto?,
    onDismiss: () -> Unit,
    onSave: (AuthorFormData) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var nativeName by remember { mutableStateOf(existing?.nativeName.orEmpty()) }
    var imageUrl by remember { mutableStateOf(existing?.imageUrl.orEmpty()) }
    var bio by remember { mutableStateOf(existing?.bio.orEmpty()) }

    EditorDialog(
        title = if (existing == null) "Add author" else "Edit author",
        onDismiss = onDismiss,
        onSave = {
            if (name.isNotBlank()) {
                onSave(AuthorFormData(name, nativeName.ifBlank { null }, imageUrl.ifBlank { null }, bio.ifBlank { null }))
            }
        }
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Author name") }, singleLine = true)
        OutlinedTextField(nativeName, { nativeName = it }, label = { Text("Native name (optional)") }, singleLine = true)
        OutlinedTextField(imageUrl, { imageUrl = it }, label = { Text("Image URL (optional)") }, singleLine = true)
        OutlinedTextField(bio, { bio = it }, label = { Text("Biography (optional)") }, minLines = 3)
    }
}

@Composable
private fun EntryEditorDialog(
    existing: MediaEntry?,
    entries: List<MediaEntry>,
    series: List<SeriesDto>,
    authors: List<AuthorDto>,
    onDismiss: () -> Unit,
    onSave: (EntryFormData) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: MediaType.NOVEL) }
    var seriesId by remember { mutableStateOf(existing?.seriesId) }
    var authorId by remember(existing?.id, authors) {
        mutableStateOf(authors.firstOrNull { existing?.authors?.contains(it.name) == true }?.id)
    }
    var createSeries by remember { mutableStateOf(false) }
    var createAuthor by remember { mutableStateOf(false) }
    var newSeries by remember { mutableStateOf("") }
    var newSeriesStatus by remember { mutableStateOf("planning") }
    var newSeriesPublishing by remember { mutableStateOf("releasing") }
    var newSeriesCurrent by remember { mutableStateOf("") }
    var newSeriesTotal by remember { mutableStateOf("") }
    var newSeriesUnit by remember { mutableStateOf("Chapter") }
    var newSeriesRating by remember { mutableStateOf("") }
    var newSeriesNotes by remember { mutableStateOf("") }
    var newAuthor by remember { mutableStateOf("") }
    var newAuthorNative by remember { mutableStateOf("") }
    var newAuthorImage by remember { mutableStateOf("") }
    var newAuthorBio by remember { mutableStateOf("") }
    var coverUrl by remember { mutableStateOf(existing?.coverUrl.orEmpty()) }
    var sourceUrl by remember(existing?.id) { mutableStateOf(existing?.sourceUrl.orEmpty()) }
    var platform by remember { mutableStateOf(existing?.platform.orEmpty()) }
    var userStatus by remember { mutableStateOf("planning") }
    var publishingStatus by remember { mutableStateOf("releasing") }
    var sequenceNumber by remember { mutableStateOf(existing?.sequenceNumber?.toString() ?: "1") }
    var currentUnit by remember { mutableStateOf(existing?.currentUnit?.toString() ?: "0") }
    var totalUnits by remember { mutableStateOf("") }
    var unitName by remember { mutableStateOf("Chapter") }
    var isCanon by remember { mutableStateOf(existing?.isCanon ?: true) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isFindingCover by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New entry" else "Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Entry title") }, singleLine = true)
                Selector("Type", type.label, MediaType.entries, { it.label }) { type = it }
                EntitySelector(
                    label = "Series",
                    selected = series.firstOrNull { it.id == seriesId }?.title ?: "Choose series",
                    options = series,
                    optionText = { it.title },
                    onSelected = { seriesId = it.id; createSeries = false },
                    onAdd = { createSeries = !createSeries }
                )
                if (createSeries) {
                    HorizontalDivider()
                    Text("New series details", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(newSeries, { newSeries = it }, label = { Text("Series name") }, singleLine = true)
                    Selector("User status", newSeriesStatus, UserStatus.entries, { it.label }) { newSeriesStatus = it.value }
                    Selector("Publishing status", newSeriesPublishing, publishingStatuses, { it }) { newSeriesPublishing = it }
                    OutlinedTextField(newSeriesCurrent, { newSeriesCurrent = it }, label = { Text("Cumulative progress") }, singleLine = true)
                    OutlinedTextField(newSeriesTotal, { newSeriesTotal = it }, label = { Text("Total units") }, singleLine = true)
                    OutlinedTextField(newSeriesUnit, { newSeriesUnit = it }, label = { Text("Unit name") }, singleLine = true)
                    OutlinedTextField(newSeriesRating, { newSeriesRating = it }, label = { Text("Rating") }, singleLine = true)
                    OutlinedTextField(newSeriesNotes, { newSeriesNotes = it }, label = { Text("Notes") }, minLines = 2)
                }
                EntitySelector(
                    label = "Author",
                    selected = authors.firstOrNull { it.id == authorId }?.name ?: "Choose author",
                    options = authors,
                    optionText = { it.name },
                    onSelected = { authorId = it.id; createAuthor = false },
                    onAdd = { createAuthor = !createAuthor }
                )
                if (createAuthor) {
                    HorizontalDivider()
                    Text("New author details", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(newAuthor, { newAuthor = it }, label = { Text("Author name") }, singleLine = true)
                    OutlinedTextField(newAuthorNative, { newAuthorNative = it }, label = { Text("Native name") }, singleLine = true)
                    OutlinedTextField(newAuthorImage, { newAuthorImage = it }, label = { Text("Image URL") }, singleLine = true)
                    OutlinedTextField(newAuthorBio, { newAuthorBio = it }, label = { Text("Biography") }, minLines = 2)
                }
                HorizontalDivider()
                Text("Media details", style = MaterialTheme.typography.titleSmall)
                Selector("User status", userStatus, UserStatus.entries, { it.label }) { userStatus = it.value }
                Selector("Publishing status", publishingStatus, publishingStatuses, { it }) { publishingStatus = it }
                OutlinedTextField(sequenceNumber, { sequenceNumber = it }, label = { Text("Sequence number") }, singleLine = true)
                OutlinedTextField(currentUnit, { currentUnit = it }, label = { Text("Current progress") }, singleLine = true)
                OutlinedTextField(totalUnits, { totalUnits = it }, label = { Text("Total units") }, singleLine = true)
                OutlinedTextField(unitName, { unitName = it }, label = { Text("Unit name") }, singleLine = true)
                OutlinedTextField(platform, { platform = it }, label = { Text("Platform") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = isCanon, onCheckedChange = { isCanon = it })
                    Text("Canon entry")
                }
                HorizontalDivider()
                Text("Publication and cover", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("Publication URL (optional)") }, singleLine = true)
                Button(
                    onClick = {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            try {
                                isFindingCover = true
                                val found = CoverLookup.findOpenGraphImage(sourceUrl)
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isFindingCover = false
                                    if (found != null) coverUrl = found
                                    else validationError = "No cover image was found at that URL."
                                }
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isFindingCover = false
                                    validationError = "Could not read the publication page."
                                }
                            }
                        }
                    },
                    enabled = sourceUrl.isNotBlank() && !isFindingCover
                ) { Text(if (isFindingCover) "Looking up cover…" else "Find cover from URL") }
                if (coverUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = coverUrl,
                        contentDescription = "Cover preview",
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                OutlinedTextField(coverUrl, { coverUrl = it }, label = { Text("Cover image URL") }, singleLine = true)
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalSeries = if (createSeries) newSeries else ""
                val finalAuthor = if (createAuthor) newAuthor else ""
                if (title.isBlank() || (seriesId == null && finalSeries.isBlank()) || (authorId == null && finalAuthor.isBlank())) {
                    validationError = "Title, series, and author are required."
                } else {
                    onSave(
                        EntryFormData(
                            title, type, seriesId, finalSeries, newSeriesStatus, newSeriesPublishing,
                            newSeriesCurrent.toDoubleOrNull(), newSeriesTotal.toDoubleOrNull(), newSeriesUnit,
                            newSeriesRating.toIntOrNull(), newSeriesNotes.ifBlank { null }, authorId,
                            if (createAuthor) AuthorFormData(newAuthor, newAuthorNative.ifBlank { null }, newAuthorImage.ifBlank { null }, newAuthorBio.ifBlank { null }) else null,
                            coverUrl, sourceUrl, platform, userStatus, publishingStatus,
                            sequenceNumber.toDoubleOrNull() ?: 1.0, currentUnit.toDoubleOrNull() ?: 0.0,
                            totalUnits.toDoubleOrNull(), unitName, isCanon
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Open $label dropdown")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                    onClick = {
                        expanded = false
                        onAdd()
                    }
                )
            }
        }
    }
}

@Composable
private fun <T> EntitySelector(
    label: String,
    selected: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T) -> Unit,
    onAdd: () -> Unit
) {
    Selector(
        label = label,
        selected = selected,
        options = options,
        text = optionText,
        onAdd = onAdd,
        onSelected = onSelected
    )
}

@Composable
private fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
