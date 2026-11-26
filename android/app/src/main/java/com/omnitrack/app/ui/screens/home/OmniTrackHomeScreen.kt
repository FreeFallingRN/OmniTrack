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
import androidx.compose.material.icons.filled.Search
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
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.OmniTrackPreferences
import androidx.compose.ui.zIndex
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.UpdateEntryRequest
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.repository.PartialMediaEntryCreateException
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
import com.omnitrack.app.ui.viewmodel.mangaBakaSeriesId
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
    val mangaBakaState by viewModel.mangaBaka.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val context = LocalContext.current
    var partialCreateMessage by remember { mutableStateOf<String?>(null) }
    var savingEntry by remember { mutableStateOf(false) }
    val prefs = remember { OmniTrackPreferences(context) }
    var selectedTabMediaType by remember {
        mutableStateOf(MediaType.fromValue(prefs.getString(OmniTrackPreferenceKeys.SelectedMediaType) ?: ""))
    }
    var selectedUserStatus by remember {
        mutableStateOf(UserStatus.fromValue(prefs.getString(OmniTrackPreferenceKeys.SelectedUserStatus) ?: ""))
    }
    val onStatusSelected: (UserStatus) -> Unit = {
        if (selectedUserStatus != it) {
            selectedUserStatus = it
            prefs.edit().putString(OmniTrackPreferenceKeys.SelectedUserStatus, it.value).apply()
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
        mutableStateOf(LibrarySortOption.fromKey(prefs.getString(OmniTrackPreferenceKeys.SortOption, "last_updated")))
    }
    var fabExpanded by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var showOnlineSearchDialog by remember { mutableStateOf(false) }
    var onlineImportUrl by remember { mutableStateOf<String?>(null) }
    var onlineImportChapters by remember { mutableStateOf<Double?>(null) }
    var scrapedPrefill by remember { mutableStateOf<ImportedMediaDraft?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.mangaBakaImportMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(mangaBakaState.editorDraft) {
        mangaBakaState.editorDraft?.let { draft ->
            draft.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            scrapedPrefill = draft
            editorEntry = null
            showEntryEditor = true
            viewModel.mangaBaka.consumeEditorDraft()
        }
    }

    LaunchedEffect(bottomNavIndex) {
        sortOption = LibrarySortOption.fromKey(prefs.getString(OmniTrackPreferenceKeys.SortOption, "last_updated"))
        if (bottomNavIndex != 0) fabExpanded = false
    }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            onSharedUrlConsumed?.invoke()

            refreshScope.launch {
                try {
                    val draft = viewModel.mangaBaka.prepareImportedDraft(
                        MediaUrlImporter.importFromUrl(context, initialSharedUrl)
                    )
                    draft.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    scrapedPrefill = draft
                    editorEntry = null
                    showEntryEditor = true
                } catch (e: CancellationException) {
                    throw e
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
                                    showOnlineSearchDialog = true
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                icon = { Icon(Icons.Default.Search, contentDescription = "Search online") },
                                text = { Text("Search online") }
                            )

                            ExtendedFloatingActionButton(
                                onClick = {
                                    fabExpanded = false
                                    onlineImportUrl = null
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
                                prefs.edit().putString(OmniTrackPreferenceKeys.SelectedMediaType, it.value).apply()
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
                mangaBakaLinked = entry.type == MediaType.COMIC &&
                    mangaBakaState.links.values.any { it.entryId == entry.id },
                relatedEntries = if (!entry.seriesId.isNullOrBlank()) {
                    mediaEntries
                        .filter { it.seriesId == entry.seriesId && it.id != entry.id }
                        .sortedWith(
                            compareBy<MediaEntry> { it.type.ordinal }
                                .thenBy { it.sequenceNumber == null }
                                .thenBy { it.sequenceNumber ?: 0.0 }
                                .thenBy { it.title.lowercase() }
                                .thenBy { it.id }
                        )
                } else {
                    emptyList()
                },
                onDismiss = { selectedEntryForDetail = null },
                onUpdateProgress = { newProgress ->
                    viewModel.updateProgress(entry.id, newProgress, ProgressSource.DETAIL_SHEET)
                },
                onUnlinkMoonReader = { entryId -> viewModel.unlinkMoonReaderBook(entryId) },
                checkMoonReaderFolder = { folder, books -> viewModel.checkMoonReaderFolder(folder, books) },
                reportMoonReaderFolderFailure = { folder, books ->
                    viewModel.setMoonReaderFolderAccess(folder, books, valid = false)
                },
                controlsEnabled = !savingStatus && !isRefreshing,
                onUpdateStatus = { status ->
                    if (status != (pendingStatus ?: entry.userStatus)) {
                        pendingStatus = status
                        savingStatus = true
                        refreshScope.launch {
                            try {
                                viewModel.updateEntryStatus(entry.id, status.value)
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

        if (showOnlineSearchDialog) {
            OnlineSearchDialog(
                onDismiss = { showOnlineSearchDialog = false },
                onSelect = { result ->
                    showOnlineSearchDialog = false
                    onlineImportUrl = result.upstreamUrl
                    onlineImportChapters = result.chapters
                    showImportUrlDialog = true
                }
            )
        }

        if (showImportUrlDialog) {
            ImportUrlDialog(
                onDismiss = {
                    showImportUrlDialog = false
                    onlineImportUrl = null
                    onlineImportChapters = null
                },
                onImport = { url ->
                    viewModel.mangaBaka.prepareImportedDraft(
                        MediaUrlImporter.importFromUrl(
                            context,
                            url,
                            fallbackTotalChapters = onlineImportChapters.takeIf { url == onlineImportUrl }
                        )
                    )
                },
                onImported = { draft ->
                    draft.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    scrapedPrefill = draft
                    editorEntry = null
                    showImportUrlDialog = false
                    onlineImportUrl = null
                    onlineImportChapters = null
                    showEntryEditor = true
                },
                initialUrl = onlineImportUrl
            )
        }

        if (showEntryEditor) {
            MediaEntryEditorSheet(
                viewModel = viewModel,
                existing = editorEntry,
                prefill = scrapedPrefill?.draft,
                reportDraft = scrapedPrefill,
                mangaBakaSeriesIds = mangaBakaState.links.filterValues { it.entryId == editorEntry?.id }.keys.toList(),
                entries = mediaEntries,
                onDismiss = {
                    showEntryEditor = false
                    scrapedPrefill = null
                },
                onDelete = if (editorEntry != null) {
                    { showDeleteWarning = true }
                } else null,
                onSave = save@{ form: MediaEntryEditorData ->
                    if (savingEntry) return@save
                    savingEntry = true
                    val importedUrl = scrapedPrefill?.normalizedUrl
                    val importedMangaBakaId = importedUrl?.let(::mangaBakaSeriesId)
                    val mangaBakaLinkUrl = scrapedPrefill?.draft?.sourceUrl
                        ?.takeIf { mangaBakaSeriesId(it) != null } ?: importedUrl
                    refreshScope.launch {
                        var createdEntryId: String? = null
                        try {
                            check(editorEntry?.aliasScopeUnverified != true) {
                                "Refresh the library and reopen this entry before editing its aliases."
                            }
                            val mergeTarget = form.mergeIntoEntryId?.let { id ->
                                check(editorEntry == null) { "Cannot merge while editing an existing entry." }
                                checkNotNull(mediaEntries.firstOrNull { it.id == id && it.type == form.type }) {
                                    "The selected merge target is no longer available for this media type."
                                }
                            }
                            check(mergeTarget?.aliasScopeUnverified != true) {
                                "Refresh the library and reopen the editor before merging this entry."
                            }
                            val groupedMatch = form.linkExistingEntryToSeriesId?.let { id ->
                                check(mergeTarget == null && editorEntry == null) { "Cannot add this entry to a series." }
                                val match = checkNotNull(mediaEntries.firstOrNull { it.id == id && it.type == form.type }) {
                                    "The selected series entry is no longer available for this media type."
                                }
                                check(match.seriesId == null || match.seriesId == form.seriesId) {
                                    "The selected entry's series changed. Please select it again."
                                }
                                match
                            }
                            val authors = if (mergeTarget != null || (groupedMatch != null && groupedMatch.seriesId == null)) {
                                viewModel.getAuthors()
                            } else emptyList()
                            val knownAuthors = authors.associate { it.name.trim().lowercase() to it.id }.toMutableMap()
                            val authorNames = authors.associate { it.id to it.name.trim().lowercase() }.toMutableMap()

                            fun savedCreators(entry: MediaEntry): List<Pair<String, String>> {
                                val creators = entry.creators.ifEmpty {
                                    entry.authors.map { EntryCreator(name = it) }
                                }
                                return creators.map { creator ->
                                    val id = creator.id ?: knownAuthors[creator.name.trim().lowercase()]
                                    checkNotNull(id) { "Could not resolve saved creator \"${creator.name}\"." }
                                    authorNames[id] = creator.name.trim().lowercase()
                                    id to creator.role
                                }
                            }
                            val existingCreators = mergeTarget?.let(::savedCreators).orEmpty()
                            val groupedCreators = groupedMatch?.takeIf { it.seriesId == null }?.let(::savedCreators).orEmpty()
                            val resolvedSeriesId: String? = when {
                                mergeTarget != null -> mergeTarget.seriesId
                                form.seriesId != null -> form.seriesId
                                !form.newSeries?.title.isNullOrBlank() -> {
                                    viewModel.createSeries(
                                        CreateSeriesRequest(title = form.newSeries.title.trim())
                                    ).id
                                }

                                else -> null
                            }

                            val importedCreatorIds = mutableMapOf<String, String>()
                            val resolvedCreators: List<Pair<String, String>> =
                                form.creators.mapNotNull { row ->
                                    val id = if (row.createNew && row.newAuthorName.isNotBlank()) {
                                        val name = row.newAuthorName.trim()
                                        val key = name.lowercase()
                                        val existingId = knownAuthors[key] ?: importedCreatorIds[key]
                                        existingId ?: viewModel.createAuthor(name).id.also {
                                            importedCreatorIds[key] = it
                                            authorNames[it] = key
                                        }
                                    } else {
                                        row.authorId
                                    }
                                    id?.let { it to row.role }
                                }

                            val resolvedPlatformId =
                                mergeTarget?.platformId ?: form.platformId ?: viewModel.createPlatform(
                                    CreatePlatformRequest(
                                        name = form.newPlatform!!.name,
                                        websiteUrl = form.newPlatform.websiteUrl,
                                        logoUrl = form.newPlatform.logoUrl
                                    )
                                ).id

                            // 1. If "Same series" was selected on a standalone match, link that existing entry to the series too
                            form.linkExistingEntryToSeriesId?.let {
                                val existingMatch = groupedMatch
                                if (existingMatch != null && existingMatch.seriesId == null && resolvedSeriesId != null) {
                                    viewModel.updateMediaEntry(
                                        id = existingMatch.id,
                                        request = UpdateEntryRequest(
                                            title = existingMatch.title,
                                            type = existingMatch.type.value,
                                            seriesId = resolvedSeriesId,
                                            platformId = existingMatch.platformId,
                                            coverUrl = existingMatch.coverUrl,
                                            sourceUrl = existingMatch.sourceUrl,
                                            userStatus = existingMatch.userStatus.value,
                                            publishingStatus = existingMatch.publishingStatus,
                                            sequenceNumber = existingMatch.sequenceNumber,
                                            currentUnit = existingMatch.currentUnit,
                                            unitName = existingMatch.unitName,
                                            isCanon = existingMatch.isCanon
                                        ),
                                        creators = groupedCreators,
                                        alternativeTitles = existingMatch.alternativeTitles
                                    )
                                }
                            }

                            // 2. Determine target ID for create vs merge/edit
                            val targetUpdateId = editorEntry?.id ?: mergeTarget?.id
                            if (targetUpdateId == null) {
                                val created = viewModel.createMediaEntry(
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
                                    ),
                                    resolvedCreators,
                                    form.alternativeTitles
                                )
                                createdEntryId = created.id
                                if (importedMangaBakaId != null && mangaBakaLinkUrl != null) {
                                    viewModel.linkImportedMangaBakaEntry(
                                        importedMangaBakaId, created.id, form.unitName, mangaBakaLinkUrl
                                    )
                                }
                            } else {
                                val mergedAliases = mergeTarget?.let { target ->
                                    (target.alternativeTitles + form.title + form.alternativeTitles)
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() && !it.equals(target.title.trim(), ignoreCase = true) }
                                        .distinctBy { it.lowercase() }
                                }
                                viewModel.updateMediaEntry(
                                    id = targetUpdateId,
                                    request = UpdateEntryRequest(
                                        title = mergeTarget?.title ?: form.title,
                                        type = mergeTarget?.type?.value ?: form.type.value,
                                        seriesId = resolvedSeriesId,
                                        platformId = resolvedPlatformId,
                                        coverUrl = mergeTarget?.coverUrl?.takeIf { it.isNotBlank() } ?: form.coverUrl,
                                        sourceUrl = mergeTarget?.sourceUrl?.takeIf { it.isNotBlank() } ?: form.sourceUrl,
                                        userStatus = mergeTarget?.userStatus?.value ?: form.userStatus,
                                        publishingStatus = mergeTarget?.publishingStatus ?: form.publishingStatus,
                                        sequenceNumber = if (mergeTarget != null) mergeTarget.sequenceNumber else form.sequenceNumber,
                                        currentUnit = mergeTarget?.currentUnit ?: form.currentUnit,
                                        unitName = mergeTarget?.unitName ?: form.unitName,
                                        isCanon = mergeTarget?.isCanon ?: form.isCanon
                                    ),
                                    creators = (existingCreators + resolvedCreators).distinctBy { (id, role) ->
                                        (authorNames[id] ?: id) to role.trim().lowercase().ifBlank { "author" }
                                    },
                                    alternativeTitles = mergedAliases ?: form.alternativeTitles
                                )

                                if (importedMangaBakaId != null && mangaBakaLinkUrl != null) {
                                    viewModel.linkImportedMangaBakaEntry(
                                        importedMangaBakaId, targetUpdateId, mergeTarget?.unitName ?: form.unitName, mangaBakaLinkUrl
                                    )
                                }
                            }

                            showEntryEditor = false
                            scrapedPrefill = null
                            viewModel.refreshData()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("OmniTrackHomeScreen", "Failed to save entry", e)
                            if (e is PartialMediaEntryCreateException || createdEntryId != null) {
                                showEntryEditor = false
                                scrapedPrefill = null
                                partialCreateMessage = if (e is PartialMediaEntryCreateException) {
                                    e.message
                                } else {
                                    "Entry $createdEntryId was created, but linking failed. Check the library and link it manually before retrying: ${e.message ?: "Unknown error"}"
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "Save failed: ${e.message ?: "Unknown error"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } finally {
                            savingEntry = false
                        }
                    }
                }
            )
        }

        partialCreateMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { partialCreateMessage = null },
                title = { Text("Entry may already exist") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { partialCreateMessage = null }) { Text("OK") }
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