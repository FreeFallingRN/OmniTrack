package com.omnitrack.app.ui.components

import android.util.Log
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
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.utils.CoverLookup
import com.omnitrack.app.utils.ScrapedMediaDraft
import com.omnitrack.app.utils.ImportedMediaDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.InputChip
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext

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
    existing: MediaEntry?,
    prefill: ScrapedMediaDraft? = null,
    reportDraft: ImportedMediaDraft? = null,
    entries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onSave: (MediaEntryEditorData) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var authors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var platforms by remember { mutableStateOf<List<PlatformDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var metadataLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            series = SupabaseService.getSeries()
            authors = SupabaseService.getAuthors()
            platforms = SupabaseService.getPlatforms()
        } catch (e: Exception) {
            error = e.message ?: "Could not load series and authors"
        } finally {
            metadataLoaded = true
        }
    }

    var title by remember { mutableStateOf(existing?.title ?: prefill?.title.orEmpty()) }
    var altTitles by remember {
        mutableStateOf(existing?.alternativeTitles ?: prefill?.alternativeTitles.orEmpty())
    }
    var newAltTitle by remember { mutableStateOf("") }
    var showAliasInput by remember { mutableStateOf(false) }

    var type by remember { mutableStateOf(existing?.type ?: prefill?.type ?: MediaType.NOVEL) }
    var seriesId by remember { mutableStateOf(existing?.seriesId) }
    var userStatus by remember { mutableStateOf(existing?.userStatus?.value ?: prefill?.userStatus ?: "planning") }
    var publishingStatus by remember {
        mutableStateOf(existing?.publishingStatus ?: prefill?.publishingStatus ?: "releasing")
    }
    var sequence by remember {
        mutableStateOf((existing?.sequenceNumber ?: prefill?.sequenceNumber)?.toString().orEmpty())
    }
    var currentUnit by remember { mutableStateOf((existing?.currentUnit ?: prefill?.currentUnit)?.toString() ?: "0") }
    var unitName by remember { mutableStateOf(existing?.unitName ?: prefill?.unitName ?: "Chapter") }
    var sourceUrl by remember { mutableStateOf(existing?.sourceUrl ?: prefill?.sourceUrl.orEmpty()) }
    var coverUrl by remember { mutableStateOf(existing?.coverUrl ?: prefill?.coverUrl.orEmpty()) }

    var creatorRows by remember { mutableStateOf(emptyList<CreatorFormRow>()) }
    var creatorDraft by remember { mutableStateOf(CreatorFormRow()) }
    var editingCreator by remember { mutableStateOf<Int?>(null) }

    var platformId by remember { mutableStateOf(existing?.platformId) }
    var createPlatform by remember { mutableStateOf(false) }
    var newPlatformName by remember { mutableStateOf(prefill?.platformHint.orEmpty()) }

    var isCanon by remember { mutableStateOf(existing?.isCanon ?: true) }
    var findingCover by remember { mutableStateOf(false) }
    var createSeries by remember { mutableStateOf(false) }
    var newSeriesTitle by remember { mutableStateOf("") }
    var newPlatformWebsiteUrl by remember { mutableStateOf("") }
    var newPlatformLogoUrl by remember { mutableStateOf("") }

    var showSeriesDetails by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    // Populate multi-creator rows once authors finish loading
    LaunchedEffect(existing?.id, prefill, metadataLoaded) {
        if (!metadataLoaded) return@LaunchedEffect

        val sourceCreators: List<Pair<String, String>> = when {
            existing != null && existing.creators.isNotEmpty() ->
                existing.creators.map { it.name to it.role }
            existing != null && existing.authors.isNotEmpty() ->
                existing.authors.map { it to "author" }
            prefill != null && prefill.creators.isNotEmpty() ->
                prefill.creators.map { it.name to it.role }
            else -> emptyList()
        }

        if (sourceCreators.isNotEmpty()) {
            creatorRows = sourceCreators.map { (rawName, role) ->
                val matched = authors.firstOrNull {
                    it.name.trim().equals(rawName.trim(), ignoreCase = true)
                }
                if (matched != null) {
                    CreatorFormRow(authorId = matched.id, role = role)
                } else {
                    CreatorFormRow(createNew = true, newAuthorName = rawName.trim(), role = role)
                }
            }
        }
    }

    LaunchedEffect(existing?.id, prefill, metadataLoaded) {
        if (!metadataLoaded) return@LaunchedEffect
        val normalizedHint = prefill?.platformHint?.replace(" ", "")?.trim()
        val matched = platforms.firstOrNull {
            it.id == existing?.platformId ||
                    (!normalizedHint.isNullOrBlank() && it.name.replace(" ", "").trim().equals(normalizedHint, ignoreCase = true))
        }
        if (matched != null) {
            platformId = matched.id
            createPlatform = false
        } else if (!prefill?.platformHint.isNullOrBlank()) {
            platformId = null
            createPlatform = true
            newPlatformName = prefill.platformHint.trim()
        }
    }

    LaunchedEffect(existing?.id, prefill, metadataLoaded) {
        if (!metadataLoaded || existing != null) return@LaunchedEffect
        val seriesTitle = prefill?.seriesTitle?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val matched = series.firstOrNull { it.title.trim().equals(seriesTitle, ignoreCase = true) }
        if (matched != null) {
            seriesId = matched.id
            createSeries = false
        } else {
            seriesId = null
            createSeries = true
            newSeriesTitle = seriesTitle
        }
    }

    // Duplicate detection against existing library entries
    fun normalizeTitleKey(raw: String): String =
        raw.lowercase()
            .replace(Regex("""@\s*comic\b|\((?:novel|manga|comic|webtoon|light novel|ln|wn)\)|:\s*season\s*\d+|season\s*\d+"""), "")
            .replace(Regex("[^a-z0-9]"), "")

    fun isSimilarTitle(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length > b.length) a else b
        // Prefix / substring containment when the shared core is substantial (>= 8 chars and >= 65% of longer)
        if (shorter.length >= 8 && longer.contains(shorter) && shorter.length.toDouble() / longer.length >= 0.65) {
            return true
        }
        return false
    }

    val duplicateMatch = remember(title, altTitles, newAltTitle, sourceUrl, type, entries, existing?.id) {
        if (existing != null) return@remember null
        val cleanUrl = sourceUrl.trim().trimEnd('/').lowercase()
        val candidateKeys = (listOf(title, newAltTitle) + altTitles)
            .map(::normalizeTitleKey)
            .filter { it.length >= 4 }
            .distinct()

        entries.firstOrNull { candidate ->
            val urlMatch = cleanUrl.isNotBlank() &&
                    !candidate.sourceUrl.isNullOrBlank() &&
                    candidate.sourceUrl.trim().trimEnd('/').lowercase() == cleanUrl

            val existingKeys = (listOf(candidate.title) + candidate.alternativeTitles + listOfNotNull(candidate.seriesTitle))
                .map(::normalizeTitleKey)
                .filter { it.length >= 4 }

            val titleMatch = existingKeys.any { ex -> candidateKeys.any { cand -> isSimilarTitle(ex, cand) } }
            urlMatch || titleMatch
        }
    }
    var mergeTargetEntry by remember { mutableStateOf<MediaEntry?>(null) }
    var groupWithEntry by remember { mutableStateOf<MediaEntry?>(null) }

    val targetEntryId = existing?.id ?: mergeTargetEntry?.id
    val position = sequence.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
        BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
    val groupedEntry = groupWithEntry?.let { selected ->
        entries.firstOrNull { it.id == selected.id && it.seriesId == null }
    }?.takeIf { seriesId != null || createSeries }
    val groupedPosition = groupedEntry?.let { it.sequenceNumber ?: 1.0 }
    val positionConflict = if (seriesId != null && position != null) {
        entries.firstOrNull {
            it.seriesId == seriesId && it.id != targetEntryId && it.sequenceNumber == position
        }
    } else null
    val groupedPositionConflict = if (seriesId != null && groupedPosition != null) {
        entries.firstOrNull {
            it.seriesId == seriesId && it.id != targetEntryId && it.sequenceNumber == groupedPosition
        }
    } else null
    val positionError = when {
        positionConflict != null -> "This position is already used by \"${positionConflict.title}\"."
        position != null && groupedPosition == position ->
            "This position will be used by \"${groupedEntry?.title}\" when it is added to the series."
        groupedPositionConflict != null ->
            "\"${groupedEntry?.title}\" cannot be added: its position is already used by \"${groupedPositionConflict.title}\"."
        else -> null
    }

    fun addAlias() {
        val cleaned = newAltTitle.trim()
        if (cleaned.isNotBlank() && altTitles.none { it.equals(cleaned, ignoreCase = true) }) {
            altTitles = altTitles + cleaned
        }
        newAltTitle = ""
    }

    fun saveEntry() {
        val hasDraft = creatorDraft.authorId != null || (creatorDraft.createNew && creatorDraft.newAuthorName.isNotBlank())
        val hasIncompleteDraft = creatorDraft.createNew && creatorDraft.newAuthorName.isBlank()
        val validCreators = if (hasDraft) {
            if (editingCreator != null) {
                creatorRows.mapIndexed { index, row -> if (index == editingCreator) creatorDraft else row }
            } else creatorRows + creatorDraft
        } else creatorRows
        val missingNewSeriesTitle = createSeries && newSeriesTitle.isBlank()
        if (hasIncompleteDraft) {
            error = "Please enter a creator name or choose an existing creator."
        } else if (title.isBlank() || missingNewSeriesTitle || validCreators.isEmpty() ||
            (platformId == null && (!createPlatform || newPlatformName.isBlank()))
        ) {
            error = if (missingNewSeriesTitle) "Please enter a name for the new series or clear it."
                else "Title, at least one creator, and platform are required."
        } else if (positionError != null) {
            showSeriesDetails = true
            error = positionError
        } else {
            val pendingAlias = newAltTitle.trim()
            val aliases = if (pendingAlias.isNotBlank() && altTitles.none { it.equals(pendingAlias, ignoreCase = true) }) {
                altTitles + pendingAlias
            } else altTitles
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
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
            Button(onClick = ::saveEntry, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text("Save")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (duplicateMatch != null && mergeTargetEntry == null && groupWithEntry == null) {
                val match = duplicateMatch
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
                                text = "Similar entry already in library",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "\"${match.title}\" (${match.type.singularLabel} • ${match.userStatus.label} • ${match.currentUnit} ${match.unitName})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        mergeTargetEntry = match
                                        groupWithEntry = null
                                        val currentParsed = currentUnit.toDoubleOrNull() ?: 0.0
                                        if (match.currentUnit > currentParsed) {
                                            currentUnit = match.currentUnit.toString()
                                        }
                                        if (userStatus == UserStatus.PLANNING.value && match.userStatus != UserStatus.PLANNING) {
                                            userStatus = match.userStatus.value
                                        }
                                        altTitles = (altTitles + match.alternativeTitles + listOf(match.title))
                                            .filter { !it.equals(title.trim(), ignoreCase = true) }
                                            .distinctBy { it.lowercase() }
                                    },
                                    label = { Text("Merge into existing") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        groupWithEntry = match
                                        mergeTargetEntry = null
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
                    }
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
                                    onClick = { type = option },
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
                                InputChip(
                                    selected = false,
                                    onClick = { altTitles = altTitles.filterNot { it == alt } },
                                    modifier = Modifier.height(36.dp),
                                    label = { Text(alt, style = MaterialTheme.typography.bodySmall) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = "Remove $alt",
                                            modifier = Modifier.height(18.dp)
                                        )
                                    }
                                )
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
                    val index = editingCreator
                    creatorRows = if (index == null) creatorRows + creatorDraft
                        else creatorRows.mapIndexed { i, row -> if (i == index) creatorDraft else row }
                    creatorDraft = CreatorFormRow()
                    editingCreator = null
                },
                platforms = platforms,
                platformId = platformId,
                onSelectPlatform = { platformId = it.id; createPlatform = false },
                onAddPlatform = { createPlatform = true; platformId = null },
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
                onSelectSeries = { seriesId = it.id; createSeries = false },
                onClearSeries = {
                    seriesId = null
                    createSeries = false
                    newSeriesTitle = ""
                    sequence = ""
                },
                onAddSeries = {
                    createSeries = true
                    seriesId = null
                    if (newSeriesTitle.isBlank()) newSeriesTitle = title
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
                item { Text("MangaBaka total chapters: $total", style = MaterialTheme.typography.bodySmall) }
            }
            publicationCoverSection(
                sourceUrl = sourceUrl,
                onSourceUrlChange = { sourceUrl = it },
                findingCover = findingCover,
                onFindCover = {
                    scope.launch {
                        try {
                            findingCover = true
                            val found = withContext(Dispatchers.IO) {
                                CoverLookup.findOpenGraphImage(sourceUrl, context)
                            }
                            findingCover = false
                            if (found == null) error = "No cover image found."
                            else coverUrl = found
                        } catch (e: Exception) {
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

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Entry editor") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}
