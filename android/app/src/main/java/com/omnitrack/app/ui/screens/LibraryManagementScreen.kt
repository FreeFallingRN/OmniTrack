package com.omnitrack.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
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
import com.omnitrack.app.ui.SeriesViewModel
import com.omnitrack.app.utils.CoverLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var editorEntry by remember { mutableStateOf<MediaEntry?>(null) }
    var showEntryEditor by remember { mutableStateOf(false) }
    var showSeriesEditor by remember { mutableStateOf(false) }
    var showAuthorEditor by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<SeriesDto?>(null) }
    var selectedAuthor by remember { mutableStateOf<AuthorDto?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Manage library", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create and maintain series, books, and authors.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Books", "Series", "Authors").forEachIndexed { index, label ->
                if (selectedSection == index) {
                    FilledTonalButton(onClick = { selectedSection = index }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { selectedSection = index }) { Text(label) }
                }
            }
        }
        HorizontalDivider()

        when (selectedSection) {
            0 -> {
                Button(onClick = { editorEntry = null; showEntryEditor = true }) {
                    Text("Add book")
                }
                entries.forEach { entry ->
                    ManagementRow(
                        title = entry.title,
                        subtitle = "${entry.seriesTitle ?: "No series"} • ${entry.authors.joinToString().ifBlank { "No author" }}",
                        onEdit = { editorEntry = entry; showEntryEditor = true },
                        onDelete = {
                            scope.launch {
                                try {
                                    SupabaseService.deleteEntry(entry.id)
                                    reloadToken++
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not delete book"
                                }
                            }
                        },
                        warning = "This permanently deletes the book, its author links, progress, and history."
                    )
                }
            }
            1 -> {
                Button(onClick = { selectedSeries = null; showSeriesEditor = true }) {
                    Text("Add series")
                }
                series.forEach { item ->
                    ManagementRow(
                        title = item.title,
                        subtitle = "${entries.count { it.seriesId == item.id }} book(s)",
                        onEdit = { selectedSeries = item; showSeriesEditor = true },
                        onDelete = {
                            scope.launch {
                                try {
                                    SupabaseService.deleteSeries(item.id)
                                    reloadToken++
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not delete series"
                                }
                            }
                        },
                        warning = "This permanently deletes the series, every book in it, their progress, and history."
                    )
                }
            }
            else -> {
                Button(onClick = { selectedAuthor = null; showAuthorEditor = true }) {
                    Text("Add author")
                }
                authors.forEach { author ->
                    ManagementRow(
                        title = author.name,
                        subtitle = "Author",
                        onEdit = { selectedAuthor = author; showAuthorEditor = true },
                        onDelete = {
                            scope.launch {
                                try {
                                    SupabaseService.deleteAuthor(author.id)
                                    reloadToken++
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not delete author"
                                }
                            }
                        },
                        warning = "This permanently deletes the author and removes the author link from every book."
                    )
                }
            }
        }
    }

    if (showSeriesEditor) {
        SeriesEditorDialog(
            existing = selectedSeries,
            onDismiss = { showSeriesEditor = false },
            onSave = { title ->
                scope.launch {
                    try {
                        if (selectedSeries == null) {
                            SupabaseService.createSeries(
                                CreateSeriesRequest(
                                    title = title,
                                    userStatus = "planning",
                                    publishingStatus = "releasing",
                                    unitName = "Chapter"
                                )
                            )
                        } else {
                            SupabaseService.updateSeries(selectedSeries!!.id, title)
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
            onSave = { name ->
                scope.launch {
                    try {
                        if (selectedAuthor == null) SupabaseService.createAuthor(name)
                        else SupabaseService.updateAuthor(selectedAuthor!!.id, name)
                        showAuthorEditor = false
                        reloadToken++
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save author"
                    }
                }
            }
        )
    }

    if (showEntryEditor) {
        EntryEditorDialog(
            existing = editorEntry,
            entries = entries,
            series = series,
            authors = authors,
            onDismiss = { showEntryEditor = false },
            onSave = { title, type, seriesId, newSeriesName, authorId, newAuthorName, coverUrl, sourceUrl ->
                scope.launch {
                    try {
                        val finalSeriesId = if (newSeriesName.isNotBlank()) {
                            SupabaseService.createSeries(
                                CreateSeriesRequest(
                                    title = newSeriesName,
                                    userStatus = "planning",
                                    publishingStatus = "releasing",
                                    unitName = "Chapter"
                                )
                            ).id
                        } else seriesId
                        val finalAuthorId = if (newAuthorName.isNotBlank()) {
                            SupabaseService.createAuthor(newAuthorName).id
                        } else authorId
                        if (finalSeriesId == null || finalAuthorId == null) {
                            error = "A book must have both a series and an author."
                            return@launch
                        }
                        if (editorEntry == null) {
                            SupabaseService.createEntry(
                                CreateEntryRequest(
                                    seriesId = finalSeriesId,
                                    title = title,
                                    type = type.value,
                                    sequenceNumber = (entries.count { it.seriesId == finalSeriesId } + 1).toDouble(),
                                    userStatus = "planning",
                                    publishingStatus = "releasing",
                                    unitName = "Chapter",
                                    sourceUrl = sourceUrl.ifBlank { null },
                                    coverUrl = coverUrl.ifBlank { null }
                                )
                            ).also { created ->
                                SupabaseService.replaceEntryAuthor(created.id, finalAuthorId)
                            }
                        } else {
                            SupabaseService.updateEntry(
                                id = editorEntry!!.id,
                                title = title,
                                type = type.value,
                                seriesId = finalSeriesId,
                                authorId = finalAuthorId,
                                coverUrl = coverUrl.ifBlank { null }
                            )
                        }
                        showEntryEditor = false
                        reloadToken++
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save book"
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    warning: String
) {
    var showWarning by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = { showWarning = true }) { Text("Delete") }
        }
    }
    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Permanent deletion") },
            text = { Text(warning) },
            confirmButton = {
                HoldToDeleteButton {
                    showWarning = false
                    onDelete()
                }
            },
            dismissButton = { TextButton(onClick = { showWarning = false }) { Text("Cancel") } }
        )
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
private fun SeriesEditorDialog(existing: SeriesDto?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    EditorDialog(title = if (existing == null) "Add series" else "Edit series", onDismiss = onDismiss, onSave = {
        if (title.isNotBlank()) onSave(title)
    }) {
        OutlinedTextField(title, { title = it }, label = { Text("Series name") }, singleLine = true)
    }
}

@Composable
private fun AuthorEditorDialog(existing: AuthorDto?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    EditorDialog(title = if (existing == null) "Add author" else "Edit author", onDismiss = onDismiss, onSave = {
        if (name.isNotBlank()) onSave(name)
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Author name") }, singleLine = true)
    }
}

@Composable
private fun EntryEditorDialog(
    existing: MediaEntry?,
    entries: List<MediaEntry>,
    series: List<SeriesDto>,
    authors: List<AuthorDto>,
    onDismiss: () -> Unit,
    onSave: (String, MediaType, String?, String, String?, String, String, String) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: MediaType.NOVEL) }
    var seriesId by remember { mutableStateOf(existing?.seriesId) }
    var authorId by remember(existing?.id, authors) {
        mutableStateOf(authors.firstOrNull { existing?.authors?.contains(it.name) == true }?.id)
    }
    var newSeries by remember { mutableStateOf("") }
    var newAuthor by remember { mutableStateOf("") }
    var coverUrl by remember { mutableStateOf(existing?.coverUrl.orEmpty()) }
    var sourceUrl by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add book" else "Edit book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Book title") }, singleLine = true)
                Selector("Type", type.label, MediaType.entries, { it.label }) { type = it }
                Selector("Series", series.firstOrNull { it.id == seriesId }?.title ?: "Choose series", series, { it.title }) { seriesId = it.id }
                OutlinedTextField(newSeries, { newSeries = it }, label = { Text("Or create a new series") }, singleLine = true)
                Selector("Author", authors.firstOrNull { it.id == authorId }?.name ?: "Choose author", authors, { it.name }) { authorId = it.id }
                OutlinedTextField(newAuthor, { newAuthor = it }, label = { Text("Or create a new author") }, singleLine = true)
                OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("Publication URL (optional)") }, singleLine = true)
                Button(
                    onClick = {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val found = CoverLookup.findOpenGraphImage(sourceUrl)
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (found != null) coverUrl = found
                                    else validationError = "No cover image was found at that URL."
                                }
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    validationError = "Could not read the publication page."
                                }
                            }
                        }
                    },
                    enabled = sourceUrl.isNotBlank()
                ) { Text("Find cover from URL") }
                OutlinedTextField(coverUrl, { coverUrl = it }, label = { Text("Cover image URL") }, singleLine = true)
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isBlank() || (seriesId == null && newSeries.isBlank()) || (authorId == null && newAuthor.isBlank())) {
                    validationError = "Title, series, and author are required."
                } else {
                    onSave(title, type, seriesId, newSeries, authorId, newAuthor, coverUrl, sourceUrl)
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
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(selected) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text(option)) },
                        onClick = { expanded = false; onSelected(option) }
                    )
                }
            }
        }
    }
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
        text = { content() },
        confirmButton = { Button(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
