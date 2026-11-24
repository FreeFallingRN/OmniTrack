package com.omnitrack.app.ui.screens.home

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.room.withTransaction
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.components.DeleteCountdownButton
import com.omnitrack.app.ui.components.MediaDetailSheet
import com.omnitrack.app.ui.components.MediaEntryEditorData
import com.omnitrack.app.ui.components.MediaEntryEditorSheet
import com.omnitrack.app.ui.components.RelatedEntityDetail
import com.omnitrack.app.ui.components.RelatedEntityDetailSheet
import com.omnitrack.app.ui.screens.library.LibraryManagementScreen
import com.omnitrack.app.ui.screens.settings.OmniTrackSettingsScreen
import com.omnitrack.app.ui.screens.stats.HistoryStatsScreen
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.LibrarySortOption
import com.omnitrack.app.utils.MediaUrlImporter
import com.omnitrack.app.utils.ImportedMediaDraft
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniTrackHomeScreen(
    viewModel: SeriesViewModel,
    initialSharedUrl: String? = null,
    onSharedUrlConsumed: (() -> Unit)? = null
) {
    val mediaEntries by viewModel.mediaEntries.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE) }
    var selectedTabMediaType by remember {
        mutableStateOf(MediaType.fromValue(prefs.getString("selected_media_type", null) ?: ""))
    }
    var selectedUserStatus by remember {
        mutableStateOf(UserStatus.fromValue(prefs.getString("selected_user_status", null) ?: ""))
    }
    val onStatusSelected: (UserStatus) -> Unit = {
        if (selectedUserStatus != it) {
            selectedUserStatus = it
            prefs.edit().putString("selected_user_status", it.value).apply()
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntryForDetail by remember { mutableStateOf<MediaEntry?>(null) }
    var selectedRelatedEntity by remember { mutableStateOf<RelatedEntityDetail?>(null) }
    var bottomNavIndex by remember { mutableIntStateOf(0) }
    var managementSection by remember { mutableIntStateOf(0) }
    var showEntryEditor by remember { mutableStateOf(false) }
    var editorEntry by remember { mutableStateOf<MediaEntry?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    var sortOption by remember {
        mutableStateOf(LibrarySortOption.fromKey(prefs.getString("sort_option", "last_updated")))
    }
    var fabExpanded by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var scrapedPrefill by remember { mutableStateOf<ImportedMediaDraft?>(null) }

    LaunchedEffect(bottomNavIndex) {
        sortOption = LibrarySortOption.fromKey(prefs.getString("sort_option", "last_updated"))
        if (bottomNavIndex != 0) fabExpanded = false
    }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            onSharedUrlConsumed?.invoke()

            refreshScope.launch {
                try {
                    val draft = MediaUrlImporter.importFromUrl(context, initialSharedUrl)
                    scrapedPrefill = draft
                    editorEntry = null
                    showEntryEditor = true
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
                // Status filter pills: only render on the Library screen when not searching
                if (bottomNavIndex == 0 && searchQuery.isBlank()) {
                    HomeLibraryStatusFilter(
                        selectedUserStatus = selectedUserStatus,
                        onStatusSelected = onStatusSelected
                    )
                }

                // Main bottom navigation bar row
                TabRow(
                    selectedTabIndex = bottomNavIndex,
                    modifier = Modifier.height(56.dp),
                    containerColor = Color.Transparent,
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
                        Triple(Icons.Default.DateRange, "Stats", 2),
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
                        HomeLibraryContent(
                            mediaEntries = mediaEntries,
                            isRefreshing = isRefreshing,
                            selectedTabMediaType = selectedTabMediaType,
                            onMediaTypeSelected = {
                                selectedTabMediaType = it
                                prefs.edit().putString("selected_media_type", it.value).apply()
                            },
                            selectedUserStatus = selectedUserStatus,
                            onStatusSelected = onStatusSelected,
                            searchQuery = searchQuery,
                            onSearchQueryChanged = { searchQuery = it },
                            sortOption = sortOption,
                            onRefresh = { viewModel.refreshData() },
                            onSelectEntry = { selectedEntryForDetail = it }
                        )
                    }

                    1 -> {
                        LibraryManagementScreen(
                            viewModel = viewModel,
                            onSelectEntry = { selectedEntryForDetail = it },
                            section = managementSection
                        )
                    }

                    2 -> {
                        HistoryStatsScreen(
                            viewModel = viewModel,
                            onSelectEntry = { selectedEntryForDetail = it }
                        )
                    }

                    3 -> {
                        // 3: Settings View
                        OmniTrackSettingsScreen(viewModel)
                    }
                }
            }
        }

        selectedEntryForDetail?.let { snapshot ->
            // Always resolve the live entry from the stream so the sheet sees the latest currentUnit
            val entry = mediaEntries.firstOrNull { it.id == snapshot.id } ?: snapshot
            var pendingStatus by remember(entry.id) { mutableStateOf<UserStatus?>(null) }
            var savingStatus by remember(entry.id) { mutableStateOf(false) }
            LaunchedEffect(entry.userStatus, pendingStatus, savingStatus) {
                if (!savingStatus && pendingStatus == entry.userStatus) pendingStatus = null
            }
            MediaDetailSheet(
                entry = entry.copy(userStatus = pendingStatus ?: entry.userStatus),
                relatedEntries = if (!entry.seriesId.isNullOrBlank()) {
                    mediaEntries
                        .filter { it.seriesId == entry.seriesId && it.id != entry.id }
                        .sortedBy { it.sequenceNumber }
                } else {
                    emptyList()
                },
                onDismiss = { selectedEntryForDetail = null },
                onUpdateProgress = { newProgress ->
                    viewModel.updateProgress(entry.id, newProgress, ProgressSource.DETAIL_SHEET)
                },
                onUnlinkMoonReader = { entryId -> viewModel.unlinkMoonReaderBook(entryId) },
                controlsEnabled = !savingStatus && !isRefreshing,
                onUpdateStatus = { status ->
                    if (status != (pendingStatus ?: entry.userStatus)) {
                        pendingStatus = status
                        savingStatus = true
                        refreshScope.launch {
                            try {
                                SupabaseService.updateEntryProgress(entry.id, null, status.value)
                                val db = AppDatabase.getInstance(context)
                                db.withTransaction {
                                    val current = checkNotNull(db.mediaEntryDao().getEntryById(entry.id))
                                    db.mediaEntryDao().updateProgressAndStatus(
                                        entry.id, current.currentUnit, status.value
                                    )
                                }
                                viewModel.refreshData()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                pendingStatus = null
                                Log.e("OmniTrackHomeScreen", "Failed to update status", e)
                                Toast.makeText(context, "Status update failed: ${e.message}", Toast.LENGTH_LONG).show()
                                viewModel.refreshData()
                            } finally {
                                savingStatus = false
                            }
                        }
                    }
                },
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
            ImportUrlDialog(
                onDismiss = { showImportUrlDialog = false },
                onImport = { url -> MediaUrlImporter.importFromUrl(context, url) },
                onImported = { draft ->
                    scrapedPrefill = draft
                    editorEntry = null
                    showImportUrlDialog = false
                    showEntryEditor = true
                }
            )
        }

        if (showEntryEditor) {
            MediaEntryEditorSheet(
                existing = editorEntry,
                prefill = scrapedPrefill?.draft,
                reportDraft = scrapedPrefill,
                entries = mediaEntries,
                onDismiss = {
                    showEntryEditor = false
                    scrapedPrefill = null
                },
                onDelete = if (editorEntry != null) {
                    { showDeleteWarning = true }
                } else null,
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

                            val resolvedCreators: List<Pair<String, String>> =
                                form.creators.mapNotNull { row ->
                                    val id = if (row.createNew && row.newAuthorName.isNotBlank()) {
                                        SupabaseService.createAuthor(row.newAuthorName.trim()).id
                                    } else {
                                        row.authorId
                                    }
                                    id?.let { it to row.role }
                                }

                            val resolvedPlatformId =
                                form.platformId ?: SupabaseService.createPlatform(
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

                            // 2. Determine target ID for create vs merge/edit
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
                                if (form.currentUnit > 0.0) {
                                    viewModel.updateProgress(
                                        created.id,
                                        form.currentUnit,
                                        ProgressSource.IMPORT
                                    )
                                }
                            } else {
                                val existingMatch =
                                    mediaEntries.firstOrNull { it.id == targetUpdateId }
                                val progressChanged =
                                    existingMatch != null && existingMatch.currentUnit != form.currentUnit

                                // Update the existing entry or merged target with all new fields & creators
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

                                if (progressChanged) {
                                    viewModel.updateProgress(
                                        targetUpdateId,
                                        form.currentUnit,
                                        ProgressSource.EDITOR
                                    )
                                }
                            }

                            showEntryEditor = false
                            scrapedPrefill = null
                            viewModel.refreshData()
                        } catch (e: Exception) {
                            Log.e("OmniTrackHomeScreen", "Failed to save entry", e)
                            Toast.makeText(
                                context,
                                "Save failed: ${e.message ?: "Unknown error"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }

        if (showDeleteWarning) {
            editorEntry?.let { entry ->
                AlertDialog(
                    onDismissRequest = { showDeleteWarning = false },
                    title = { Text("Delete entry permanently?") },
                    text = { Text("This removes the entry, its author links, progress, and history.") },
                    confirmButton = {
                        DeleteCountdownButton {
                            viewModel.deleteMediaEntry(entry.id)
                            showDeleteWarning = false
                            showEntryEditor = false
                            selectedEntryForDetail = null
                            editorEntry = null
                            scrapedPrefill = null
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteWarning = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}