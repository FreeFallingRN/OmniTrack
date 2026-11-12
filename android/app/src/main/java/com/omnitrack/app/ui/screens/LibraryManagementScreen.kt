package com.omnitrack.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.data.model.UpdatePlatformRequest
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.ui.SeriesViewModel
import com.omnitrack.app.ui.components.DeleteCountdownButton
import com.omnitrack.app.utils.CoverLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SeriesFormData(
    val title: String,
    val rating: Int?
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

@Composable
fun LibraryManagementScreen(
    viewModel: SeriesViewModel,
    onSelectEntry: (MediaEntry) -> Unit = {},
    section: Int = 0
) {
    val entries by viewModel.mediaEntries.collectAsState()
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var authors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var platforms by remember { mutableStateOf<List<PlatformDto>>(emptyList()) }
    var selectedSection by remember { mutableIntStateOf(0) }
    LaunchedEffect(section) { selectedSection = section }
    var reloadToken by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                series = SupabaseService.getSeries()
                authors = SupabaseService.getAuthors()
                platforms = SupabaseService.getPlatforms()
                viewModel.refreshData()
            } catch (e: Exception) {
                error = e.message ?: "Could not load library management data"
            }
        }
    }

    LaunchedEffect(reloadToken) { reload() }

    var showSeriesEditor by remember { mutableStateOf(false) }
    var showAuthorEditor by remember { mutableStateOf(false) }
    var showPlatformEditor by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<PlatformDto?>(null) }
    var detailTitle by remember { mutableStateOf<String?>(null) }
    var detailSubtitle by remember { mutableStateOf<String?>(null) }
    var detailBody by remember { mutableStateOf<String?>(null) }
    var detailImageUrl by remember { mutableStateOf<String?>(null) }
    var detailWorks by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var detailUserStatus by remember { mutableStateOf<String?>(null) }
    var detailPublishingStatus by remember { mutableStateOf<String?>(null) }
    var detailEdit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var selectedSeries by remember { mutableStateOf<SeriesDto?>(null) }
    var selectedAuthor by remember { mutableStateOf<AuthorDto?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedKind by remember { mutableStateOf<String?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }

    Scaffold(
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
                "Manage series, authors, and platforms.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf("Serie", "Author", "Platform").forEachIndexed { index, label ->
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

            HorizontalDivider()

            when (selectedSection) {
            0 -> {
                series.forEach { item ->
                    ManagementRow(
                        title = item.title,
                        subtitle = seriesEntryCountLabel(entries.filter { it.seriesId == item.id }),
                        imageUrl = entries
                            .filter { it.seriesId == item.id && !it.coverUrl.isNullOrBlank() }
                            .maxByOrNull { it.sequenceNumber }
                            ?.coverUrl,
                        selected = false,
                        onClick = {
                            val seriesEntries = entries.filter { it.seriesId == item.id }
                            detailTitle = item.title
                            detailSubtitle = null
                            detailBody = null
                            detailImageUrl = seriesEntries
                                .filter { !it.coverUrl.isNullOrBlank() }
                                .maxByOrNull { it.sequenceNumber }
                                ?.coverUrl
                            detailWorks = seriesEntries
                            detailUserStatus = UserStatus.fromValue(derivedSeriesStatus(entries, item.id)).label
                            detailPublishingStatus = derivedSeriesPublishingStatus(entries, item.id)
                            selectedId = item.id
                            selectedKind = "series"
                            detailEdit = {
                                selectedSeries = item
                                detailTitle = null
                                showSeriesEditor = true
                            }
                        },
                        onLongClick = { }
                    )
                }
            }
            else -> {
                if (selectedSection == 1) {
                authors.forEach { author ->
                    ManagementRow(
                        title = author.name,
                        subtitle = "Author",
                        selected = false,
                        onClick = {
                            selectedId = author.id
                            selectedKind = "author"
                            detailTitle = author.name
                            detailSubtitle = "Author"
                            detailBody = listOfNotNull(author.nativeName, author.imageUrl, author.bio)
                                .joinToString("\n")
                            detailEdit = {
                                selectedAuthor = author
                                detailTitle = null
                                showAuthorEditor = true
                            }
                        },
                        onLongClick = { }
                    )
                }
                }
                if (selectedSection == 2) {
                    platforms.forEach { platform ->
                        ManagementRow(
                            title = platform.name,
                            subtitle = "Platform",
                            imageUrl = platform.logoUrl,
                            selected = false,
                            onClick = {
                                selectedId = platform.id
                                selectedKind = "platform"
                                detailTitle = platform.name
                                detailSubtitle = "Platform"
                                detailBody = null
                                detailEdit = {
                                    selectedPlatform = platform
                                    detailTitle = null
                                    showPlatformEditor = true
                                }
                            },
                            onLongClick = { }
                        )
                    }
                }
            }
            }
        }
    }

    if (showDeleteWarning && selectedId != null) {
        val selectedSeriesItem = series.firstOrNull { it.id == selectedId }
        val selectedAuthorItem = authors.firstOrNull { it.id == selectedId }
        val selectedPlatformItem = platforms.firstOrNull { it.id == selectedId }
        val warning = when (selectedKind) {
            "series" -> "This permanently deletes ${selectedSeriesItem?.title ?: "the series"}, every entry in it, their progress, and history."
            "platform" -> "This permanently deletes ${selectedPlatformItem?.name ?: "the platform"} and clears it from linked entries."
            else -> "This permanently deletes ${selectedAuthorItem?.name ?: "the author"} and removes the author link from every entry."
        }
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            title = { Text("Permanent deletion") },
            text = { Text(warning) },
            confirmButton = {
                DeleteCountdownButton {
                    scope.launch {
                        try {
                            when (selectedKind) {
                                "series" -> SupabaseService.deleteSeries(selectedId!!)
                                "author" -> SupabaseService.deleteAuthor(selectedId!!)
                                "platform" -> SupabaseService.deletePlatform(selectedId!!)
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
                                    rating = form.rating
                                )
                            )
                        } else {
                            SupabaseService.updateSeries(
                                selectedSeries!!.id,
                                form.title,
                                form.rating
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

    if (showPlatformEditor) {
        PlatformEditorSheet(
            existing = selectedPlatform,
            onDismiss = { showPlatformEditor = false },
            onSave = { name, websiteUrl, logoUrl ->
                scope.launch {
                    try {
                        selectedPlatform?.let {
                            SupabaseService.updatePlatform(
                                it.id,
                                UpdatePlatformRequest(
                                    name = name,
                                    websiteUrl = websiteUrl,
                                    logoUrl = logoUrl
                                )
                            )
                        }
                        showPlatformEditor = false
                        reloadToken++
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save platform"
                    }
                }
            }
        )
    }

    detailTitle?.let { title ->
        EntityDetailSheet(
            title = title,
            subtitle = detailSubtitle,
            body = detailBody,
            imageUrl = detailImageUrl,
            works = detailWorks,
            userStatus = detailUserStatus,
            publishingStatus = detailPublishingStatus,
            onDismiss = { detailTitle = null },
            onEdit = { detailEdit?.invoke() },
            onSelectWork = onSelectEntry,
            onDelete = if (selectedKind != null) {
                { detailTitle = null; showDeleteWarning = true }
            } else null
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
    imageUrl: String? = null,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "$title logo",
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun seriesEntryCountLabel(entries: List<MediaEntry>): String =
    entries
        .groupingBy { it.type }
        .eachCount()
        .entries
        .sortedBy { it.key.label }
        .joinToString(", ") { (type, count) ->
            val label = if (count == 1) type.singularLabel else type.label
            "$count ${label.lowercase()}"
        }
        .ifBlank { "No works" }

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

    EditorDialog(
        title = if (existing == null) "Add series" else "Edit series",
        onDismiss = onDismiss,
        onSave = {
            if (title.isNotBlank()) {
                onSave(
                    SeriesFormData(
                        title, rating.toIntOrNull()
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlatformEditorSheet(
    existing: PlatformDto?,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var websiteUrl by remember { mutableStateOf(existing?.websiteUrl.orEmpty()) }
    var logoUrl by remember { mutableStateOf(existing?.logoUrl.orEmpty()) }
    var findingLogo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Edit platform", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Platform name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                websiteUrl,
                { websiteUrl = it },
                label = { Text("Website URL (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        findingLogo = true
                        val found = CoverLookup.findOpenGraphImage(websiteUrl)
                        withContext(Dispatchers.Main) {
                            findingLogo = false
                            if (found != null) logoUrl = found
                        }
                    }
                },
                enabled = websiteUrl.isNotBlank() && !findingLogo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (findingLogo) "Finding logo…" else "Find logo from website")
            }
            if (logoUrl.isNotBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = "Platform logo preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(96.dp)
                )
            }
            OutlinedTextField(
                logoUrl,
                { logoUrl = it },
                label = { Text("Logo image URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name, websiteUrl.ifBlank { null }, logoUrl.ifBlank { null })
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeriesStatusPill(status: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EntityDetailSheet(
    title: String,
    subtitle: String?,
    body: String?,
    imageUrl: String?,
    works: List<MediaEntry>,
    userStatus: String?,
    publishingStatus: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSelectWork: (MediaEntry) -> Unit,
    onDelete: (() -> Unit)?
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "$title cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                userStatus?.let { SeriesStatusPill(it) }
                publishingStatus?.let { SeriesStatusPill(it.toDisplayLabel()) }
            }

            body?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (works.isNotEmpty()) {
                Text("Works", style = MaterialTheme.typography.titleMedium)
                works.sortedBy { it.sequenceNumber }.forEach { work ->
                    Card(
                        modifier = Modifier.clickable { onSelectWork(work) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(work.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${work.type.singularLabel} • ${work.userStatus.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${work.unitName} ${work.currentUnit.toString().removeSuffix(".0")}",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                }
            }

            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text("Edit")
            }

            onDelete?.let {
                androidx.compose.material3.OutlinedButton(
                    onClick = it,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete")
                }
            }

        }
    }
}
