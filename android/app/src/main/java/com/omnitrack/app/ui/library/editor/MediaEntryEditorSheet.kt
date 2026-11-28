package com.omnitrack.app.ui.library.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnitrack.app.data.AppLogger
import com.omnitrack.app.domain.model.Author
import com.omnitrack.app.domain.model.Platform
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.PublishingStatus
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.scrapeissues.ScrapeIssueReportDialog
import com.omnitrack.app.ui.scraping.RescrapePreviewDialog
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.ImportedMediaDraft
import com.omnitrack.app.utils.scraper.CoverNormalizer
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.InputChip
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetState
import com.omnitrack.app.ui.scraping.RescrapeChange
import com.omnitrack.app.ui.scraping.RescrapeField

data class CreatorFormRow(
    val authorId: String? = null,
    val newAuthorName: String = "",
    val createNew: Boolean = false,
    val role: String = "author"
)

data class MediaEntryEditorData(
    val title: String,
    val alternativeTitles: List<String>,
    val type: MediaType,
    val seriesId: String?,
    val newSeries: NewSeriesData?,
    val creators: List<CreatorFormRow>,
    val userStatus: String,
    val publishingStatus: String,
    val sequenceNumber: Double?,
    val currentUnit: Double,
    val unitName: String,
    val platformId: String?,
    val newPlatform: NewPlatformData?,
    val sourceUrl: String?,
    val coverUrl: String?,
    val isCanon: Boolean,
    val mergeIntoEntryId: String? = null,
    val linkExistingEntryToSeriesId: String? = null
)

data class NewSeriesData(
    val title: String,
)

data class NewPlatformData(
    val name: String,
    val websiteUrl: String?,
    val logoUrl: String?
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaEntryEditorSheet(
    viewModel: SeriesViewModel,
    existing: MediaEntry?,
    prefill: ScrapedMediaDraft? = null,
    reportDraft: ImportedMediaDraft? = null,
    mangaBakaSeriesIds: List<Long> = emptyList(),
    entries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onSave: (MediaEntryEditorData) -> Unit,
    onDelete: (() -> Unit)? = null,
    saving: Boolean = false
) {
    val draftKey = existing?.id to if (existing == null) prefill else null
    var series by remember(draftKey) { mutableStateOf<List<Series>>(emptyList()) }
    var authors by remember(draftKey) { mutableStateOf<List<Author>>(emptyList()) }
    var platforms by remember(draftKey) { mutableStateOf<List<Platform>>(emptyList()) }
    var error by remember(draftKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mediaLookup = LocalFeatureServices.current.mediaLookup
    val aniListState by LocalFeatureServices.current.aniList.state.collectAsState()
    val aniListProgressLocked = existing?.type == MediaType.ANIME &&
        aniListState.links.values.any { it.entryId == existing.id }
    var metadataLoaded by remember(draftKey) { mutableStateOf(false) }

    var title by remember(draftKey) { mutableStateOf(existing?.title ?: prefill?.title.orEmpty()) }
    var altTitles by remember(draftKey) {
        mutableStateOf(existing?.alternativeTitles ?: prefill?.alternativeTitles.orEmpty())
    }
    var newAltTitle by remember(draftKey) { mutableStateOf("") }
    var showAliasInput by remember(draftKey) { mutableStateOf(false) }

    var type by remember(draftKey) { mutableStateOf(existing?.type ?: prefill?.type ?: MediaType.NOVEL) }
    var seriesId by remember(draftKey) { mutableStateOf(existing?.seriesId) }
    var userStatus by remember(draftKey) {
        mutableStateOf(existing?.userStatus?.value ?: prefill?.userStatus ?: "planning")
    }
    var publishingStatus by remember(draftKey) {
        mutableStateOf(existing?.publishingStatus ?: prefill?.publishingStatus ?: "releasing")
    }
    var sequence by remember(draftKey) {
        mutableStateOf((existing?.sequenceNumber ?: prefill?.sequenceNumber)?.toString().orEmpty())
    }
    var currentUnit by remember(draftKey) {
        mutableStateOf((existing?.currentUnit ?: prefill?.currentUnit)?.toString() ?: "0")
    }
    LaunchedEffect(aniListProgressLocked, existing?.currentUnit) {
        if (aniListProgressLocked) currentUnit = existing.currentUnit.toString()
    }
    var unitName by remember(draftKey) {
        mutableStateOf(existing?.unitName ?: prefill?.unitName ?: "Chapter")
    }
    var sourceUrl by remember(draftKey) { mutableStateOf(existing?.sourceUrl ?: prefill?.sourceUrl.orEmpty()) }
    var coverUrl by remember(draftKey) { mutableStateOf(existing?.coverUrl ?: prefill?.coverUrl.orEmpty()) }

    var creatorRows by remember(draftKey) { mutableStateOf(emptyList<CreatorFormRow>()) }
    var creatorDraft by remember(draftKey) { mutableStateOf(CreatorFormRow()) }
    var editingCreator by remember(draftKey) { mutableStateOf<Int?>(null) }

    var platformId by remember(draftKey) { mutableStateOf(existing?.platformId) }
    var createPlatform by remember(draftKey) { mutableStateOf(false) }
    var newPlatformName by remember(draftKey) { mutableStateOf(prefill?.platformHint.orEmpty()) }

    var isCanon by remember(draftKey) { mutableStateOf(existing?.isCanon ?: true) }
    var findingCover by remember(draftKey) { mutableStateOf(false) }
    var rescraping by remember(draftKey) { mutableStateOf(false) }
    var rescraped by remember(draftKey) { mutableStateOf<ScrapedMediaDraft?>(null) }
    var createSeries by remember(draftKey) { mutableStateOf(false) }
    var newSeriesTitle by remember(draftKey) { mutableStateOf("") }
    var newPlatformWebsiteUrl by remember(draftKey) { mutableStateOf("") }
    var newPlatformLogoUrl by remember(draftKey) { mutableStateOf("") }

    var showSeriesDetails by remember(draftKey) { mutableStateOf(false) }
    var showReportDialog by remember(draftKey) { mutableStateOf(false) }

    LaunchedEffect(viewModel, draftKey) {
        try {
            series = viewModel.getSeries()
            authors = viewModel.getAuthors()
            platforms = viewModel.getPlatforms()
            val sourceCreators: List<Pair<String, String>> = when {
                existing != null && existing.creators.isNotEmpty() ->
                    existing.creators.map { it.name to it.role }
                existing != null && existing.authors.isNotEmpty() ->
                    existing.authors.map { it to "author" }
                prefill != null && prefill.creators.isNotEmpty() ->
                    prefill.creators.map { it.name to it.role }
                else -> emptyList()
            }
            creatorRows = creatorRowsForNames(sourceCreators, authors)
            val normalizedHint = prefill?.platformHint?.replace(" ", "")?.trim()
            val matchedPlatform = platforms.firstOrNull {
                it.id == existing?.platformId ||
                    (!normalizedHint.isNullOrBlank() && it.name.replace(" ", "").trim().equals(normalizedHint, ignoreCase = true))
            }
            if (matchedPlatform != null) {
                platformId = matchedPlatform.id
                createPlatform = false
            } else if (!prefill?.platformHint.isNullOrBlank()) {
                platformId = null
                createPlatform = true
                newPlatformName = prefill.platformHint.trim()
            }
            val seriesTitle = prefill?.seriesTitle?.trim()?.takeIf { existing == null && it.isNotBlank() }
            if (seriesTitle != null) {
                val matchedSeries = series.firstOrNull { it.title.trim().equals(seriesTitle, ignoreCase = true) }
                if (matchedSeries != null) {
                    seriesId = matchedSeries.id
                    createSeries = false
                } else {
                    seriesId = null
                    createSeries = true
                    newSeriesTitle = seriesTitle
                }
            }
            metadataLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error("[MediaEntryEditor] Could not load editor metadata", e)
            error = e.message ?: "Could not load series and authors"
        }
    }

    val similarMatches = remember(title, altTitles, newAltTitle, sourceUrl, type, entries, existing?.id) {
        if (existing == null) {
            findSimilarEntries(title, altTitles, newAltTitle, sourceUrl, type, entries, null)
        } else {
            emptyList()
        }
    }
    var mergeTargetEntry by remember(draftKey) { mutableStateOf<MediaEntry?>(null) }
    var groupWithEntry by remember(draftKey) { mutableStateOf<MediaEntry?>(null) }
    var previousSeriesId by remember(draftKey) { mutableStateOf<String?>(null) }
    var previousCreateSeries by remember(draftKey) { mutableStateOf(false) }
    var previousNewSeriesTitle by remember(draftKey) { mutableStateOf("") }

    fun clearMatchSelection() {
        if (groupWithEntry != null) {
            seriesId = previousSeriesId
            createSeries = previousCreateSeries
            newSeriesTitle = previousNewSeriesTitle
        }
        mergeTargetEntry = null
        groupWithEntry = null
    }

    val targetEntryId = existing?.id ?: mergeTargetEntry?.id
    val sameSeriesTitleConflict = if (existing == null && mergeTargetEntry == null && title.isNotBlank()) {
        entries.firstOrNull {
            it.type == type && it.title.trim().equals(title.trim(), ignoreCase = true) &&
                ((seriesId != null && it.seriesId == seriesId) ||
                    (groupWithEntry?.id == it.id && it.seriesId == null && createSeries))
        }
    } else null
    val positionError = entryPositionError(sequence, seriesId, targetEntryId, groupWithEntry, createSeries, type, entries)

    fun addAlias() {
        altTitles = appendAlias(altTitles, title, newAltTitle)
        newAltTitle = ""
    }

    fun makeMainTitle(alias: String) {
        val newTitle = alias.trim()
        altTitles = aliasesAfterMainTitleChange(altTitles, title, newTitle)
        title = newTitle
    }

    fun saveEntry() {
        val validCreators = mergeCreatorDraft(creatorRows, creatorDraft, editingCreator)
        val validationError = validateEntryDraft(
            creatorDraft, validCreators, publishingStatus, mergeTargetEntry, groupWithEntry,
            entries, type, title, createSeries, newSeriesTitle, platformId, createPlatform,
            newPlatformName, positionError
        )
        if (validationError != null) {
            AppLogger.warn("[MediaEntryEditor] Save validation failed: $validationError")
            if (mergeTargetEntry == null && positionError != null && validationError == positionError) {
                showSeriesDetails = true
            }
            error = validationError
        } else {
            val aliases = appendAlias(altTitles, title, newAltTitle)
            onSave(
                MediaEntryEditorData(
                    title = title.trim(),
                    alternativeTitles = aliases,
                    type = type,
                    seriesId = seriesId,
                    newSeries = if (createSeries && newSeriesTitle.isNotBlank()) NewSeriesData(title = newSeriesTitle.trim()) else null,
                    creators = validCreators,
                    platformId = platformId,
                    newPlatform = if (createPlatform) NewPlatformData(
                        name = newPlatformName,
                        websiteUrl = newPlatformWebsiteUrl.ifBlank { null },
                        logoUrl = newPlatformLogoUrl.ifBlank { null }
                    ) else null,
                    userStatus = userStatus,
                    publishingStatus = publishingStatus,
                    sequenceNumber = sequence.toDoubleOrNull(),
                    currentUnit = (currentUnit.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                    unitName = unitName,
                    sourceUrl = sourceUrl.ifBlank { null },
                    coverUrl = coverUrl.ifBlank { null },
                    isCanon = isCanon,
                    mergeIntoEntryId = mergeTargetEntry?.id,
                    linkExistingEntryToSeriesId = groupWithEntry?.id
                )
            )
        }
    }

    val currentSaving by rememberUpdatedState(saving)
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(
                SheetValue.Hidden,
                SheetValue.PartiallyExpanded,
                SheetValue.Expanded
            ),
            confirmValueChange = { !currentSaving || it != SheetValue.Hidden }
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, enabled = !saving) {
                Icon(Icons.Default.Close, contentDescription = "Close editor")
            }
            Text(
                text = if (existing == null) "New entry" else "Edit entry",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (reportDraft != null) {
                IconButton(onClick = { showReportDialog = true }) {
                    Icon(Icons.Default.Flag, contentDescription = "Report issue")
                }
            }
            Button(
                onClick = ::saveEntry,
                enabled = !saving && metadataLoaded,
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…")
                } else {
                    Text("Save")
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (existing != null) {
                item {
                    OutlinedButton(
                        enabled = !rescraping && metadataLoaded,
                        onClick = {
                            scope.launch {
                                rescraping = true
                                try {
                                    val url = sourceUrl.trim().ifBlank {
                                        check(mangaBakaSeriesIds.size <= 1) {
                                            "Multiple MangaBaka links found. Enter the source URL to rescrape."
                                        }
                                        mangaBakaSeriesIds.singleOrNull()?.let { "https://mangabaka.org/series/$it" }
                                            ?: throw IllegalStateException("No source URL or MangaBaka link is set for this entry.")
                                    }
                                    val result = mediaLookup.scrape(url, type, requireFetchedMetadata = true).draft
                                    check(result.title.isNotBlank()) { "The source did not return a title." }
                                    rescraped = result
                                    AppLogger.info("[MediaEntryEditor] Rescrape completed for entry ${existing.id}")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.error("[MediaEntryEditor] Rescrape failed", e)
                                    error = e.message ?: "Could not fetch details from the source."
                                } finally {
                                    rescraping = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (rescraping) "Rescraping..." else "Rescrape")
                    }
                }
            }
            if (existing == null && (similarMatches.isNotEmpty() || mergeTargetEntry != null || groupWithEntry != null)) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Similar titles in your library",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                "Choose an entry or keep none selected. Merge preserves the existing entry's main title and saved fields.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            FilterChip(
                                selected = mergeTargetEntry == null && groupWithEntry == null,
                                onClick = ::clearMatchSelection,
                                label = { Text("None") }
                            )
                            mergeTargetEntry?.let {
                                Text("Merging into \"${it.title}\".", style = MaterialTheme.typography.bodySmall)
                            }
                            similarMatches.forEach { (match, entryMatch) ->
                                Text(
                                    text = "\"${match.title}\" (${match.type.singularLabel} • ${match.userStatus.label} • ${match.currentUnit} ${match.unitName})" +
                                        (match.seriesTitle?.let { " • Series: $it" } ?: "") +
                                        (match.platform?.let { " • Platform: $it" } ?: "") +
                                        if (entryMatch) "" else " • Series title match only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (entryMatch) {
                                        FilterChip(
                                            selected = mergeTargetEntry?.id == match.id,
                                            onClick = {
                                                clearMatchSelection()
                                                mergeTargetEntry = match
                                            },
                                            label = { Text("Merge") }
                                        )
                                    }
                                    FilterChip(
                                        selected = groupWithEntry?.id == match.id,
                                        onClick = {
                                            clearMatchSelection()
                                            previousSeriesId = seriesId
                                            previousCreateSeries = createSeries
                                            previousNewSeriesTitle = newSeriesTitle
                                            groupWithEntry = match
                                            showSeriesDetails = true
                                            if (match.seriesId != null) {
                                                seriesId = match.seriesId
                                                createSeries = false
                                            } else {
                                                seriesId = null
                                                createSeries = true
                                                newSeriesTitle = match.title
                                            }
                                        },
                                        label = { Text("Same series") }
                                    )
                                }
                            }
                            groupWithEntry?.let {
                                Text("A new entry will be added to the series, not merged into \"${it.title}\".",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            sameSeriesTitleConflict?.let { conflict ->
                item {
                    Text(
                        "Warning: \"${conflict.title}\" already has this exact main title in this series (${conflict.type.singularLabel}). Saving adds another entry.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            title, { title = it },
                            label = { Text("Entry title") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Text("Type", style = MaterialTheme.typography.labelMedium)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            MediaType.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = type == option,
                                    onClick = {
                                        if (type != option) clearMatchSelection()
                                        type = option
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index, MediaType.entries.size),
                                    icon = {},
                                    contentPadding = PaddingValues(horizontal = 2.dp)
                                ) {
                                    Text(option.singularLabel, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                        Text("Alternative titles", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            altTitles.forEach { alt ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    InputChip(
                                        selected = false,
                                        onClick = { altTitles = altTitles.filterNot { it == alt } },
                                        modifier = Modifier.weight(1f, fill = false).height(36.dp),
                                        label = { Text(alt, style = MaterialTheme.typography.bodySmall) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Remove $alt",
                                                modifier = Modifier.height(18.dp)
                                            )
                                        }
                                    )
                                    TextButton(
                                        onClick = { makeMainTitle(alt) },
                                        enabled = alt.isNotBlank(),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Set as main")
                                    }
                                }
                            }
                            InputChip(
                                selected = showAliasInput,
                                onClick = {
                                    if (showAliasInput) addAlias()
                                    showAliasInput = !showAliasInput
                                },
                                modifier = Modifier.height(36.dp),
                                label = { Text("Alias") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                        if (showAliasInput) {
                            OutlinedTextField(
                                value = newAltTitle,
                                onValueChange = { newAltTitle = it },
                                label = { Text("Add alias / native title") },
                                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                                        addAlias()
                                        true
                                    } else false
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { addAlias() }),
                                trailingIcon = {
                                    IconButton(onClick = ::addAlias, enabled = newAltTitle.isNotBlank()) {
                                        Icon(Icons.Default.Add, contentDescription = "Add alias")
                                    }
                                }
                            )
                        }
                    }
                }
            }
            creatorSection(
                authors = authors,
                creatorRows = creatorRows,
                onRemoveCreator = { removedIndex ->
                    val editedIndex = editingCreator
                    if (editedIndex != null) {
                        when {
                            removedIndex == editedIndex -> {
                                editingCreator = null
                                creatorDraft = CreatorFormRow()
                            }
                            removedIndex < editedIndex -> editingCreator = editedIndex - 1
                        }
                    }
                    creatorRows = creatorRows.filterIndexed { index, _ -> index != removedIndex }
                },
                creatorDraft = creatorDraft,
                onCreatorDraftChange = { creatorDraft = it },
                editingCreator = editingCreator,
                onEditCreator = { index ->
                    if (creatorDraft.createNew && creatorDraft.newAuthorName.isBlank()) {
                        error = "Please enter a creator name or choose an existing creator."
                    } else if (isDuplicateCreatorRole(creatorRows, creatorDraft, editingCreator)) {
                        error = "This creator already has that role."
                    } else {
                        val editedIndex = editingCreator
                        if (creatorDraft.authorId != null || creatorDraft.createNew) {
                            creatorRows = if (editedIndex == null) creatorRows + creatorDraft
                                else creatorRows.mapIndexed { i, row -> if (i == editedIndex) creatorDraft else row }
                        }
                        editingCreator = index
                        creatorDraft = creatorRows[index]
                    }
                },
                onAddCreator = {
                    if (isDuplicateCreatorRole(creatorRows, creatorDraft, editingCreator)) {
                        error = "This creator already has that role."
                        return@creatorSection
                    }
                    val index = editingCreator
                    creatorRows = if (index == null) creatorRows + creatorDraft
                        else creatorRows.mapIndexed { i, row -> if (i == index) creatorDraft else row }
                    creatorDraft = CreatorFormRow()
                    editingCreator = null
                },
                platforms = platforms,
                platformId = platformId,
                onSelectPlatform = { platformId = it.id; createPlatform = false },
                onAddPlatform = { name ->
                    createPlatform = true
                    platformId = null
                    if (name != null) newPlatformName = name
                },
                createPlatform = createPlatform,
                newPlatformName = newPlatformName,
                onNewPlatformNameChange = { newPlatformName = it },
                newPlatformWebsiteUrl = newPlatformWebsiteUrl,
                onNewPlatformWebsiteUrlChange = { newPlatformWebsiteUrl = it }
            )
            seriesSection(
                showSeriesDetails = showSeriesDetails,
                onToggle = { showSeriesDetails = !showSeriesDetails },
                series = series,
                seriesId = seriesId,
                seriesTitle = existing?.seriesTitle?.takeIf { existing.seriesId == seriesId },
                createSeries = createSeries,
                onSelectSeries = {
                    if (groupWithEntry != null) clearMatchSelection()
                    seriesId = it.id
                    createSeries = false
                },
                onClearSeries = {
                    if (groupWithEntry != null) clearMatchSelection()
                    seriesId = null
                    createSeries = false
                    newSeriesTitle = ""
                    sequence = ""
                },
                onAddSeries = { name ->
                    if (groupWithEntry != null) clearMatchSelection()
                    createSeries = true
                    seriesId = null
                    if (name != null) newSeriesTitle = name
                    else if (newSeriesTitle.isBlank()) newSeriesTitle = title
                },
                newSeriesTitle = newSeriesTitle,
                onNewSeriesTitleChange = { newSeriesTitle = it },
                sequence = sequence,
                onSequenceChange = { sequence = it },
                positionError = positionError
            )
            mediaSection(
                userStatus = userStatus,
                onUserStatusChange = { userStatus = it },
                publishingStatus = publishingStatus,
                onPublishingStatusChange = { publishingStatus = it },
                currentUnit = currentUnit,
                progressLocked = aniListProgressLocked,
                onCurrentUnitChange = { newValue ->
                    val sanitized = newValue.replace("-", "")
                    currentUnit = sanitized
                    val parsed = sanitized.toDoubleOrNull() ?: 0.0
                    if (parsed > 0.0 && userStatus == UserStatus.PLANNING.value) {
                        userStatus = UserStatus.IN_PROGRESS.value
                    }
                },
                unitName = unitName,
                onUnitNameChange = { unitName = it },
                isCanon = isCanon,
                onIsCanonChange = { isCanon = it }
            )
            prefill?.totalChapters?.let { total ->
                val unit = prefill.unitName.trim().ifBlank { "chapter" }.lowercase()
                val pluralUnit = if (unit.endsWith("s", ignoreCase = true)) unit else "${unit}s"
                item {
                    Text(
                        "Source total $pluralUnit: $total (informational; not stored on entries)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            publicationCoverSection(
                sourceUrl = sourceUrl,
                onSourceUrlChange = { sourceUrl = it },
                findingCover = findingCover,
                onFindCover = {
                    scope.launch {
                        try {
                            findingCover = true
                            val found = mediaLookup.findCover(sourceUrl)
                            findingCover = false
                            if (found == null) {
                                AppLogger.warn("[MediaEntryEditor] Cover lookup returned no image")
                                error = "No cover image found."
                            } else coverUrl = found
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error("[MediaEntryEditor] Cover lookup failed", e)
                            findingCover = false
                            error = e.message ?: "Could not find cover image"
                        }
                    }
                },
                coverUrl = coverUrl,
                onCoverUrlChange = { coverUrl = it }
            )
            if (existing != null && onDelete != null) {
                item {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
                }
            }
        }
    }

    if (showReportDialog && reportDraft != null) {
        ScrapeIssueReportDialog(reportDraft) { showReportDialog = false }
    }

    rescraped?.let { draft ->
        val pendingCreators = mergeCreatorDraft(creatorRows, creatorDraft, editingCreator)
        val currentCreators = pendingCreators.map { row ->
            val name = if (row.createNew) row.newAuthorName else authors.firstOrNull { it.id == row.authorId }?.name.orEmpty()
            name to row.role
        }
        val fetchedCreators = draft.creators.map { it.name.trim() to it.role }.filter { it.first.isNotBlank() }
        val currentAliases = (altTitles + listOf(newAltTitle.trim()).filter { it.isNotBlank() }).distinct()
        val fetchedAliases = draft.alternativeTitles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val changes = buildList {
            if (draft.title.isNotBlank() && draft.title != title) {
                add(RescrapeChange(RescrapeField.TITLE, "Title", title, draft.title))
            }
            if (!draft.coverUrl.isNullOrBlank() &&
                CoverNormalizer.canonicalizeForComparison(draft.coverUrl) !=
                CoverNormalizer.canonicalizeForComparison(coverUrl)
            ) {
                add(RescrapeChange(RescrapeField.COVER, "Cover URL", coverUrl, draft.coverUrl))
            }
            if (draft.publishingStatus in PublishingStatus.values && draft.publishingStatus != publishingStatus) {
                add(RescrapeChange(RescrapeField.STATUS, "Publishing status", publishingStatus, draft.publishingStatus))
            }
            if (fetchedCreators.isNotEmpty() && fetchedCreators != currentCreators) {
                add(RescrapeChange(
                    RescrapeField.CREATORS, "Creators",
                    currentCreators.joinToString { "${it.first} (${it.second})" },
                    fetchedCreators.joinToString { "${it.first} (${it.second})" }
                ))
            }
            if (fetchedAliases.isNotEmpty() && fetchedAliases != currentAliases) {
                add(RescrapeChange(RescrapeField.ALIASES, "Alternative titles", currentAliases.joinToString(), fetchedAliases.joinToString()))
            }
        }
        RescrapePreviewDialog(
            changes = changes,
            totalChapters = draft.totalChapters,
            totalUnitName = draft.unitName,
            onDismiss = { rescraped = null },
            onApply = { selected ->
                AppLogger.debug("[MediaEntryEditor] Applying ${selected.size} rescraped fields")
                if (RescrapeField.TITLE in selected) title = draft.title
                if (RescrapeField.COVER in selected) coverUrl = draft.coverUrl.orEmpty()
                if (RescrapeField.STATUS in selected && draft.publishingStatus in PublishingStatus.values) {
                    publishingStatus = draft.publishingStatus
                }
                if (RescrapeField.ALIASES in selected) {
                    altTitles = fetchedAliases
                    newAltTitle = ""
                }
                if (RescrapeField.CREATORS in selected) {
                    creatorRows = creatorRowsForNames(fetchedCreators, authors)
                    creatorDraft = CreatorFormRow()
                    editingCreator = null
                }
                rescraped = null
            }
        )
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
