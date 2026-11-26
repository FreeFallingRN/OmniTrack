package com.omnitrack.app.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.data.model.UpdatePlatformRequest
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.ui.components.DeleteCountdownButton
import com.omnitrack.app.ui.components.EntityDetailSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    var searchQuery by remember { mutableStateOf("") }
    val query = searchQuery.trim()
    LaunchedEffect(section) { selectedSection = section }
    var reloadToken by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var hasLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            if (!hasLoaded) isInitialLoading = true
            loadError = null
            try {
                series = viewModel.getSeries()
                authors = viewModel.getAuthors()
                platforms = viewModel.getPlatforms()
                hasLoaded = true
                viewModel.refreshData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: "Could not load library management data"
                if (hasLoaded) error = loadError
            } finally {
                isInitialLoading = false
            }
        }
    }

    LaunchedEffect(reloadToken) { reload() }

    var showSeriesEditor by remember { mutableStateOf(false) }
    var showAuthorEditor by remember { mutableStateOf(false) }
    var showPlatformEditor by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<PlatformDto?>(null) }
    var showDetail by remember { mutableStateOf(false) }
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
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Manage library", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Manage series, authors, and platforms.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TabRow(
                selectedTabIndex = selectedSection,
                containerColor = Color.Transparent,
                indicator = { tabPositions ->
                    if (selectedSection < tabPositions.size) {
                        Box(
                            modifier = Modifier
                                .tabIndicatorOffset(tabPositions[selectedSection])
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .zIndex(-1f)
                        )
                    }
                },
                divider = {}
            ) {
                listOf("Series", "Author", "Platform").forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    selectedSection = index
                                    selectedId = null
                                    selectedKind = null
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                            Text(
                                text = label,
                                color = if (selectedSection == index) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${listOf("series", "authors", "platforms")[selectedSection]}...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent
                )
            )

            HorizontalDivider()

            AnimatedContent(
                targetState = selectedSection,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "management section transition"
            ) { section ->
                if (!hasLoaded && isInitialLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (!hasLoaded && loadError != null) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(loadError ?: "", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { reload() }) { Text("Retry") }
                    }
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (section) {
                        0 -> {
                            val matches = series.filter { it.title.contains(query, ignoreCase = true) }
                            if (matches.isEmpty()) {
                                item { Text(if (query.isEmpty()) "No series yet" else "No matching series") }
                            }
                            items(matches, key = { it.id }) { item ->
                                val works = entries.filter { it.seriesId == item.id }
                                ManagementRow(
                                    title = item.title,
                                    subtitle = seriesEntryCountLabel(works),
                                    imageUrl = works
                                        .filter { !it.coverUrl.isNullOrBlank() }
                                        .maxByOrNull { it.sequenceNumber ?: Double.NEGATIVE_INFINITY }
                                        ?.coverUrl,
                                    selected = false,
                                    onClick = {
                                        selectedId = item.id
                                        selectedKind = "series"
                                        showDetail = true
                                    },
                                    onLongClick = { }
                                )
                            }
                        }
                        1 -> {
                            val matches = authors.filter {
                                it.name.contains(query, ignoreCase = true) ||
                                    it.nativeName?.contains(query, ignoreCase = true) == true
                            }
                            if (matches.isEmpty()) {
                                item { Text(if (query.isEmpty()) "No authors yet" else "No matching authors") }
                            }
                            items(matches, key = { it.id }) { author ->
                                val works = entries.filter { entry -> entry.creators.any { it.id == author.id } }
                                ManagementRow(
                                    title = author.name,
                                    subtitle = "Author · ${seriesEntryCountLabel(works)}",
                                    imageUrl = author.imageUrl,
                                    selected = false,
                                    onClick = {
                                        selectedId = author.id
                                        selectedKind = "author"
                                        showDetail = true
                                    },
                                    onLongClick = { }
                                )
                            }
                        }
                        2 -> {
                            val matches = platforms.filter { it.name.contains(query, ignoreCase = true) }
                            if (matches.isEmpty()) {
                                item { Text(if (query.isEmpty()) "No platforms yet" else "No matching platforms") }
                            }
                            items(matches, key = { it.id }) { platform ->
                                val works = entries.filter { it.platformId == platform.id }
                                ManagementRow(
                                    title = platform.name,
                                    subtitle = "Platform · ${seriesEntryCountLabel(works)}",
                                    imageUrl = platform.logoUrl,
                                    showPlatformIcon = true,
                                    selected = false,
                                    onClick = {
                                        selectedId = platform.id
                                        selectedKind = "platform"
                                        showDetail = true
                                    },
                                    onLongClick = { }
                                )
                            }
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
                                "series" -> viewModel.deleteSeries(selectedId!!)
                                "author" -> viewModel.deleteAuthor(selectedId!!)
                                "platform" -> viewModel.deletePlatform(selectedId!!)
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
            derivedUserStatus = selectedSeries?.takeIf { series -> entries.any { it.seriesId == series.id } }
                ?.let { derivedSeriesStatus(entries, it.id) },
            derivedPublishingStatus = selectedSeries?.takeIf { series -> entries.any { it.seriesId == series.id } }?.let {
                derivedSeriesPublishingStatus(entries, it.id)
            },
            onDismiss = { showSeriesEditor = false },
            onDelete = selectedSeries?.let {
                { showSeriesEditor = false; showDeleteWarning = true }
            },
            onSave = { form ->
                scope.launch {
                    try {
                        if (selectedSeries == null) {
                            viewModel.createSeries(
                                CreateSeriesRequest(
                                    title = form.title,
                                    rating = form.rating
                                )
                            )
                        } else {
                            viewModel.updateSeries(
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
            onDelete = selectedAuthor?.let {
                { showAuthorEditor = false; showDeleteWarning = true }
            },
            onSave = { form ->
                scope.launch {
                    try {
                        if (selectedAuthor == null) {
                            viewModel.createAuthor(
                                form.name,
                                form.nativeName,
                                form.imageUrl,
                                form.bio
                            )
                        } else {
                            viewModel.updateAuthor(
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
            onDelete = selectedPlatform?.let {
                { showPlatformEditor = false; showDeleteWarning = true }
            },
            onSave = { name, websiteUrl, logoUrl ->
                scope.launch {
                    try {
                        selectedPlatform?.let {
                            viewModel.updatePlatform(
                                it.id,
                                UpdatePlatformRequest(
                                    name = name,
                                    websiteUrl = websiteUrl,
                                    logoUrl = logoUrl
                                )
                            )
                        } ?: viewModel.createPlatform(
                            CreatePlatformRequest(
                                name = name,
                                websiteUrl = websiteUrl,
                                logoUrl = logoUrl
                            )
                        )
                        showPlatformEditor = false
                        reloadToken++
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save platform"
                    }
                }
            }
        )
    }

    val detailId = selectedId
    if (showDetail && detailId != null) {
        val seriesItem = series.firstOrNull { selectedKind == "series" && it.id == detailId }
        val authorItem = authors.firstOrNull { selectedKind == "author" && it.id == detailId }
        val platformItem = platforms.firstOrNull { selectedKind == "platform" && it.id == detailId }
        val title = seriesItem?.title ?: authorItem?.name ?: platformItem?.name
        val works = when (selectedKind) {
            "series" -> entries.filter { it.seriesId == detailId }
            "author" -> entries.filter { entry -> entry.creators.any { it.id == detailId } }
            "platform" -> entries.filter { it.platformId == detailId }
            else -> emptyList()
        }
        if (title != null) {
            val count = seriesEntryCountLabel(works)
            EntityDetailSheet(
                title = title,
                subtitle = when (selectedKind) {
                    "author" -> "Author · $count"
                    "platform" -> "Platform · $count"
                    else -> count
                },
                body = when (selectedKind) {
                    "author" -> listOfNotNull(authorItem?.nativeName, authorItem?.bio)
                        .filter { it.isNotBlank() }.joinToString("\n")
                    "platform" -> platformItem?.websiteUrl
                    else -> null
                },
                imageUrl = when (selectedKind) {
                    "series" -> works
                        .filter { !it.coverUrl.isNullOrBlank() }
                        .maxByOrNull { it.sequenceNumber ?: Double.NEGATIVE_INFINITY }
                        ?.coverUrl
                    "author" -> authorItem?.imageUrl
                    "platform" -> platformItem?.logoUrl
                    else -> null
                },
                works = works,
                userStatus = if (seriesItem != null && works.isNotEmpty()) {
                    UserStatus.fromValue(derivedSeriesStatus(entries, detailId)).label
                } else null,
                publishingStatus = if (seriesItem != null && works.isNotEmpty()) {
                    derivedSeriesPublishingStatus(entries, detailId)
                } else null,
                onDismiss = { showDetail = false },
                onEdit = {
                    showDetail = false
                    when (selectedKind) {
                        "series" -> { selectedSeries = seriesItem; showSeriesEditor = true }
                        "author" -> { selectedAuthor = authorItem; showAuthorEditor = true }
                        "platform" -> { selectedPlatform = platformItem; showPlatformEditor = true }
                    }
                },
                onSelectWork = {
                    showDetail = false
                    onSelectEntry(it)
                },
                onDelete = null
            )
        }
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
    showPlatformIcon: Boolean = false,
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
            if (showPlatformIcon && !imageUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = "$title logo",
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit,
                    loading = {
                        Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    error = {
                        Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            } else if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "$title logo",
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
            } else if (showPlatformIcon) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
