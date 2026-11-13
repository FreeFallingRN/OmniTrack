package com.omnitrack.app.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.ui.SeriesViewModel
import com.omnitrack.app.ui.components.MediaDetailSheet
import com.omnitrack.app.ui.components.RelatedEntityDetail
import com.omnitrack.app.ui.components.RelatedEntityDetailSheet
import com.omnitrack.app.ui.components.MediaEntryEditorData
import com.omnitrack.app.ui.components.MediaEntryEditorSheet
import com.omnitrack.app.ui.components.TrackedMediaRow
import kotlinx.coroutines.launch
import com.omnitrack.app.utils.LibrarySortOption
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.draw.rotate
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.MediaUrlImporter
import com.omnitrack.app.data.model.CreateEntryRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniTrackHomeScreen(
    viewModel: SeriesViewModel
) {
    val mediaEntries by viewModel.mediaEntries.collectAsState()

    var selectedTabMediaType by remember { mutableStateOf(MediaType.NOVEL) }
    var selectedUserStatus by remember { mutableStateOf(UserStatus.IN_PROGRESS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntryForDetail by remember { mutableStateOf<MediaEntry?>(null) }
    var selectedRelatedEntity by remember { mutableStateOf<RelatedEntityDetail?>(null) }
    var bottomNavIndex by remember { mutableIntStateOf(0) }
    var managementSection by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showEntryEditor by remember { mutableStateOf(false) }
    var editorEntry by remember { mutableStateOf<MediaEntry?>(null) }
    val refreshScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE) }
    var sortOption by remember {
        mutableStateOf(LibrarySortOption.fromKey(prefs.getString("sort_option", "last_updated")))
    }
    var fabExpanded by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var scrapedPrefill by remember { mutableStateOf<ScrapedMediaDraft?>(null) }

    LaunchedEffect(bottomNavIndex) {
        sortOption = LibrarySortOption.fromKey(prefs.getString("sort_option", "last_updated"))
        if (bottomNavIndex != 0) fabExpanded = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (bottomNavIndex == 0) {
                val rotation by animateFloatAsState(
                    targetValue = if (fabExpanded) 45f else 0f,
                    label = "fab rotation"
                )

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(
                        visible = fabExpanded,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    fabExpanded = false
                                    showImportUrlDialog = true
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = "Import from link"
                                    )
                                },
                                text = { Text("Import from link") }
                            )

                            ExtendedFloatingActionButton(
                                onClick = {
                                    fabExpanded = false
                                    editorEntry = null
                                    scrapedPrefill = null
                                    showEntryEditor = true
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Custom entry"
                                    )
                                },
                                text = { Text("Custom entry") }
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = { fabExpanded = !fabExpanded }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (fabExpanded) "Close menu" else "Add media",
                            modifier = Modifier.rotate(rotation)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Status filter pills: only render at the bottom when on the Library screen (index 0)
                if (bottomNavIndex == 0) {
                        ScrollableTabRow(
                            selectedTabIndex = selectedUserStatus.ordinal,
                            edgePadding = 0.dp,
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            indicator = { tabPositions ->
                                if (selectedUserStatus.ordinal < tabPositions.size) {
                                    Box(
                                        modifier = Modifier
                                            .tabIndicatorOffset(tabPositions[selectedUserStatus.ordinal])
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
                            UserStatus.entries.forEach { status ->
                                Box(
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { selectedUserStatus = status }
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = status.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selectedUserStatus == status) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        },
                                        fontWeight = if (selectedUserStatus == status) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                }
                            }
                        }
                }

                // Main bottom navigation bar row
                TabRow(
                    selectedTabIndex = bottomNavIndex,
                    modifier = Modifier.height(56.dp),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    indicator = { tabPositions ->
                        if (bottomNavIndex < tabPositions.size) {
                            Box(
                                modifier = Modifier
                                    .tabIndicatorOffset(tabPositions[bottomNavIndex])
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .zIndex(-1f)
                            )
                        }
                    },
                    divider = {}
                ) {
                    listOf(
                        Triple(Icons.Default.LocalLibrary, "Library", 0),
                        Triple(Icons.Default.AccountTree, "Manage library", 1),
                        Triple(Icons.Default.DateRange, "History", 2),
                        Triple(Icons.Default.Settings, "Settings", 3)
                    ).forEach { (icon, label, index) ->
                        Box(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { bottomNavIndex = index }
                                )
                                .padding(horizontal = 32.dp, vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (bottomNavIndex == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Switch content based on active bottom nav tab index
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = bottomNavIndex,
                transitionSpec = {
                    (scaleIn(initialScale = 0.9f) + fadeIn()) togetherWith
                        (scaleOut() + fadeOut())
                },
                label = "bottom navigation page transition"
            ) { page ->
            when (page) {
                0 -> {
                    // 0: Library View (Search bar, media tabs, and list)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search library...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Media Type Tabs (Novels, Comics, Anime, Movies)
                        ScrollableTabRow(
                            selectedTabIndex = selectedTabMediaType.ordinal,
                            edgePadding = 0.dp,
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            indicator = { tabPositions ->
                                if (selectedTabMediaType.ordinal < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabMediaType.ordinal]),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            divider = {}
                        ) {
                            MediaType.entries.forEach { mediaType ->
                                Tab(
                                    selected = selectedTabMediaType == mediaType,
                                    onClick = { selectedTabMediaType = mediaType },
                                    text = {
                                        Text(
                                            text = mediaType.label,
                                            fontWeight = if (selectedTabMediaType == mediaType) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedTabMediaType == mediaType) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                refreshScope.launch {
                                    isRefreshing = true
                                    viewModel.refreshData()
                                    isRefreshing = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            AnimatedContent(
                                targetState = selectedUserStatus,
                                transitionSpec = {
                                    if (targetState.ordinal > initialState.ordinal) {
                                        slideInHorizontally { it } + fadeIn() togetherWith
                                            (slideOutHorizontally { -it } + fadeOut())
                                    } else {
                                        slideInHorizontally { -it } + fadeIn() togetherWith
                                            (slideOutHorizontally { it } + fadeOut())
                                    }
                                },
                                label = "library status filter transition"
                            ) { status ->
                            // Filtered Media List
                            val filteredList = mediaEntries.filter { entry ->
                                val matchesType = entry.type == selectedTabMediaType
                                val matchesStatus = entry.userStatus == status
                                val matchesSearch = searchQuery.isBlank() ||
                                    entry.title.contains(searchQuery, ignoreCase = true) ||
                                    entry.seriesTitle?.contains(searchQuery, ignoreCase = true) == true
                                matchesType && matchesStatus && matchesSearch
                            }.let { list ->
                                when (sortOption) {
                                    LibrarySortOption.LAST_UPDATED -> list.sortedByDescending { it.updatedAt }
                                    LibrarySortOption.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
                                    LibrarySortOption.READ_CHAPTERS -> list.sortedByDescending { it.currentUnit }
                                }
                            }

                            if (filteredList.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No entries found",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(filteredList, key = { it.id }) { entry ->
                                        TrackedMediaRow(
                                            entry = entry,
                                            onClick = { selectedEntryForDetail = entry },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
                1 -> {
                    LibraryManagementScreen(
                        viewModel = viewModel,
                        onSelectEntry = { selectedEntryForDetail = it },
                        section = managementSection
                    )
                }
                2 -> {
                    // 2: History View
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("History Screen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                3 -> {
                    // 3: Settings View
                    OmniTrackSettingsScreen()
                }
            }
        }
    }

    selectedEntryForDetail?.let { entry ->
        MediaDetailSheet(
            entry = entry,
            relatedEntries = if (!entry.seriesId.isNullOrBlank()) {
                mediaEntries
                    .filter { it.seriesId == entry.seriesId && it.id != entry.id }
                    .sortedBy { it.sequenceNumber }
            } else {
                emptyList()
            },
            onDismiss = { selectedEntryForDetail = null },
            onUpdateProgress = { newProgress -> viewModel.updateProgress(entry.id, newProgress) },
            onSelectEntry = { selectedEntryForDetail = it },
            onOpenRelatedEntity = {
                selectedRelatedEntity = it
                selectedEntryForDetail = null
            },
            onEdit = {
                editorEntry = entry
                selectedEntryForDetail = null
                scrapedPrefill = null
                showEntryEditor = true
            },
            onDelete = {
                refreshScope.launch {
                    SupabaseService.deleteEntry(entry.id)
                    selectedEntryForDetail = null
                    viewModel.refreshData()
                }
            }
        )
    }

    selectedRelatedEntity?.let { detail ->
        RelatedEntityDetailSheet(
            detail = detail,
            onDismiss = { selectedRelatedEntity = null },
            onSelectWork = {
                selectedRelatedEntity = null
                selectedEntryForDetail = it
            }
        )
    }

        if (showImportUrlDialog) {
            var importUrl by remember { mutableStateOf("") }
            var isImporting by remember { mutableStateOf(false) }
            var importError by remember { mutableStateOf<String?>(null) }

            AlertDialog(
                onDismissRequest = { if (!isImporting) showImportUrlDialog = false },
                title = { Text("Import from link") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Paste a URL from MyAnimeList, Royal Road, MangaFire, or any supported site.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = {
                                importUrl = it
                                importError = null
                            },
                            label = { Text("Media URL") },
                            singleLine = true,
                            enabled = !isImporting,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (importError != null) {
                            Text(
                                text = importError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = importUrl.isNotBlank() && !isImporting,
                        onClick = {
                            refreshScope.launch {
                                isImporting = true
                                importError = null
                                try {
                                    val draft = MediaUrlImporter.importFromUrl(context, importUrl)
                                    scrapedPrefill = draft
                                    editorEntry = null
                                    showImportUrlDialog = false
                                    showEntryEditor = true
                                } catch (e: Exception) {
                                    importError = e.message ?: "Failed to fetch media details"
                                } finally {
                                    isImporting = false
                                }
                            }
                        }
                    ) {
                        Text(if (isImporting) "Importing..." else "Fetch")
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isImporting,
                        onClick = { showImportUrlDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showEntryEditor) {
            MediaEntryEditorSheet(
                existing = editorEntry,
                prefill = scrapedPrefill,
                entries = mediaEntries,
                onDismiss = {
                    showEntryEditor = false
                    scrapedPrefill = null
                },
                onSave = { form: MediaEntryEditorData ->
                    refreshScope.launch {
                        try {
                            val resolvedSeriesId: String? = when {
                                form.seriesId != null -> form.seriesId
                                !form.newSeries?.title.isNullOrBlank() -> {
                                    SupabaseService.createSeries(
                                        CreateSeriesRequest(title = form.newSeries.title.trim())
                                    ).id
                                }
                                else -> null
                            }

                            val resolvedCreators: List<Pair<String, String>> = form.creators.mapNotNull { row ->
                                val id = if (row.createNew && row.newAuthorName.isNotBlank()) {
                                    SupabaseService.createAuthor(row.newAuthorName.trim()).id
                                } else {
                                    row.authorId
                                }
                                id?.let { it to row.role }
                            }

                            val resolvedPlatformId = form.platformId ?: SupabaseService.createPlatform(
                                CreatePlatformRequest(
                                    name = form.newPlatform!!.name,
                                    websiteUrl = form.newPlatform.websiteUrl,
                                    logoUrl = form.newPlatform.logoUrl
                                )
                            ).id

                            // 1. If "Same series" was selected on a standalone match, link that existing entry to the series too
                            form.linkExistingEntryToSeriesId?.let { existingId ->
                                val existingMatch = mediaEntries.firstOrNull { it.id == existingId }
                                if (existingMatch != null && existingMatch.seriesId == null && resolvedSeriesId != null) {
                                    SupabaseService.updateEntry(
                                        id = existingMatch.id,
                                        title = existingMatch.title,
                                        type = existingMatch.type.value,
                                        seriesId = resolvedSeriesId,
                                        creators = existingMatch.creators.mapNotNull { c -> c.id?.let { it to c.role } },
                                        platformId = existingMatch.platformId,
                                        coverUrl = existingMatch.coverUrl,
                                        sourceUrl = existingMatch.sourceUrl,
                                        userStatus = existingMatch.userStatus.value,
                                        publishingStatus = existingMatch.publishingStatus,
                                        sequenceNumber = existingMatch.sequenceNumber ?: 1.0,
                                        currentUnit = existingMatch.currentUnit,
                                        unitName = existingMatch.unitName,
                                        isCanon = existingMatch.isCanon,
                                        alternativeTitles = existingMatch.alternativeTitles
                                    )
                                }
                            }

                            // 2. Create a new entry OR update the edited / merged entry
                            val targetUpdateId = editorEntry?.id ?: form.mergeIntoEntryId
                            if (targetUpdateId == null) {
                                val created = SupabaseService.createEntry(
                                    CreateEntryRequest(
                                        seriesId = resolvedSeriesId,
                                        title = form.title,
                                        type = form.type.value,
                                        sequenceNumber = form.sequenceNumber,
                                        userStatus = form.userStatus,
                                        publishingStatus = form.publishingStatus,
                                        isCanon = form.isCanon,
                                        currentUnit = form.currentUnit,
                                        unitName = form.unitName,
                                        sourceUrl = form.sourceUrl,
                                        platformId = resolvedPlatformId,
                                        coverUrl = form.coverUrl
                                    )
                                )
                                SupabaseService.replaceEntryAuthors(created.id, resolvedCreators)
                                SupabaseService.replaceEntryAlternateTitles(
                                    entryId = created.id,
                                    seriesId = resolvedSeriesId,
                                    titles = form.alternativeTitles
                                )
                            } else {
                                SupabaseService.updateEntry(
                                    id = targetUpdateId,
                                    title = form.title,
                                    type = form.type.value,
                                    seriesId = resolvedSeriesId,
                                    creators = resolvedCreators,
                                    platformId = resolvedPlatformId,
                                    coverUrl = form.coverUrl,
                                    sourceUrl = form.sourceUrl,
                                    userStatus = form.userStatus,
                                    publishingStatus = form.publishingStatus,
                                    sequenceNumber = form.sequenceNumber,
                                    currentUnit = form.currentUnit,
                                    unitName = form.unitName,
                                    isCanon = form.isCanon,
                                    alternativeTitles = form.alternativeTitles
                                )
                            }

                            showEntryEditor = false
                            scrapedPrefill = null
                            viewModel.refreshData()
                        } catch (e: Exception) {
                            android.util.Log.e("OmniTrackHomeScreen", "Failed to save entry", e)
                            android.widget.Toast.makeText(
                                context,
                                "Save failed: ${e.message ?: "Unknown error"}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }
    }
}
