package com.omnitrack.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.PublishingStatus
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.utils.CoverLookup
import com.omnitrack.app.utils.ScrapedMediaDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.InputChip
import androidx.compose.material3.Surface
import com.omnitrack.app.domain.model.CreatorRole

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaEntryEditorSheet(
    existing: MediaEntry?,
    prefill: ScrapedMediaDraft? = null,
    entries: List<MediaEntry>,
    onDismiss: () -> Unit,
    onSave: (MediaEntryEditorData) -> Unit
) {
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var authors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var platforms by remember { mutableStateOf<List<PlatformDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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

    var type by remember { mutableStateOf(existing?.type ?: prefill?.type ?: MediaType.NOVEL) }
    var seriesId by remember { mutableStateOf(existing?.seriesId) }
    var userStatus by remember { mutableStateOf(existing?.userStatus?.value ?: "planning") }
    var publishingStatus by remember {
        mutableStateOf(existing?.publishingStatus ?: prefill?.publishingStatus ?: "releasing")
    }
    var sequence by remember { mutableStateOf(existing?.sequenceNumber?.toInt()?.toString().orEmpty()) }
    var currentUnit by remember { mutableStateOf(existing?.currentUnit?.toString() ?: "0") }
    var unitName by remember { mutableStateOf(existing?.unitName ?: prefill?.unitName ?: "Chapter") }
    var sourceUrl by remember { mutableStateOf(existing?.sourceUrl ?: prefill?.sourceUrl.orEmpty()) }
    var coverUrl by remember { mutableStateOf(existing?.coverUrl ?: prefill?.coverUrl.orEmpty()) }

    var creatorRows by remember { mutableStateOf(listOf(CreatorFormRow())) }

    var platformId by remember { mutableStateOf(existing?.platformId) }
    var createPlatform by remember { mutableStateOf(false) }
    var newPlatformName by remember { mutableStateOf(prefill?.platformHint.orEmpty()) }

    var isCanon by remember { mutableStateOf(existing?.isCanon ?: true) }
    var findingCover by remember { mutableStateOf(false) }
    var createSeries by remember { mutableStateOf(false) }
    var newSeriesTitle by remember { mutableStateOf("") }
    var newPlatformWebsiteUrl by remember { mutableStateOf("") }
    var newPlatformLogoUrl by remember { mutableStateOf("") }

    var showSeriesDetails by remember { mutableStateOf(existing?.seriesId != null) }
    var showMediaDetails by remember { mutableStateOf(false) }

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

    val duplicateMatch = remember(title, altTitles, sourceUrl, type, entries, existing?.id) {
        if (existing != null) return@remember null
        val cleanUrl = sourceUrl.trim().trimEnd('/').lowercase()
        val candidateKeys = (listOf(title) + altTitles)
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
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("Entry title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Alternative titles", style = MaterialTheme.typography.labelMedium)
                    if (altTitles.isNotEmpty()) {
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
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newAltTitle,
                            onValueChange = { newAltTitle = it },
                            label = { Text("Add alias / native title") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(
                            onClick = {
                                val cleaned = newAltTitle.trim()
                                if (cleaned.isNotBlank() && altTitles.none { it.equals(cleaned, ignoreCase = true) }) {
                                    altTitles = altTitles + cleaned
                                }
                                newAltTitle = ""
                            },
                            enabled = newAltTitle.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }
                }
            }
            item {
                StatusButtonGroup(
                    label = "Type",
                    selected = type.value,
                    options = MediaType.entries.map { it.value to it.singularLabel },
                    onSelected = { value ->
                        type = MediaType.fromValue(value)
                    }
                )
            }
            item {
                Text("Creators", style = MaterialTheme.typography.titleMedium)
            }
            creatorRows.forEachIndexed { index, row ->
                item(key = "creator_row_$index") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                Selector(
                                    label = "Creator ${index + 1}",
                                    selected = when {
                                        row.createNew -> "New: ${row.newAuthorName.ifBlank { "Enter name below" }}"
                                        else -> authors.firstOrNull { it.id == row.authorId }?.name ?: "Choose creator"
                                    },
                                    options = authors,
                                    text = { it.name },
                                    onAdd = {
                                        creatorRows = creatorRows.toMutableList().also {
                                            it[index] = row.copy(createNew = true, authorId = null)
                                        }
                                    }
                                ) { selected ->
                                    creatorRows = creatorRows.toMutableList().also {
                                        it[index] = row.copy(authorId = selected.id, createNew = false)
                                    }
                                }
                            }
                            if (creatorRows.size > 1) {
                                IconButton(
                                    onClick = {
                                        creatorRows = creatorRows.toMutableList().also { it.removeAt(index) }
                                    }
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Remove creator")
                                }
                            }
                        }

                        if (row.createNew) {
                            OutlinedTextField(
                                value = row.newAuthorName,
                                onValueChange = { updatedName ->
                                    creatorRows = creatorRows.toMutableList().also {
                                        it[index] = row.copy(newAuthorName = updatedName)
                                    }
                                },
                                label = { Text("New creator name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        StatusButtonGroup(
                            label = "Role",
                            selected = row.role,
                            options = CreatorRole.values.map { it to it.toDisplayLabel() },
                            compact = true,
                            onSelected = { newRole ->
                                creatorRows = creatorRows.toMutableList().also {
                                    it[index] = row.copy(role = newRole)
                                }
                            }
                        )
                    }
                }
            }
            item {
                TextButton(
                    onClick = { creatorRows = creatorRows + CreatorFormRow(role = "illustrator") }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add another creator / artist")
                }
            }
            item {
                Selector(
                    "Platform",
                    platforms.firstOrNull { it.id == platformId }?.name ?: "Choose platform",
                    platforms,
                    { it.name },
                    onAdd = { createPlatform = true; platformId = null }
                ) { platformId = it.id; createPlatform = false }
            }
            if (createPlatform) {
                item { HorizontalDivider() }
                item { Text("New platform details", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(
                        newPlatformName,
                        { newPlatformName = it },
                        label = { Text("Platform name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        newPlatformWebsiteUrl,
                        { newPlatformWebsiteUrl = it },
                        label = { Text("Website URL (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Collapsible Series Details Section
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSeriesDetails = !showSeriesDetails }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Series details",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (showSeriesDetails) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (showSeriesDetails) "Collapse series details" else "Expand series details",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (showSeriesDetails) {
                item {
                    Selector(
                        label = "Series (optional)",
                        selected = series.firstOrNull { it.id == seriesId }?.title ?: "None (Standalone)",
                        options = series,
                        text = { it.title },
                        onClear = if (seriesId != null || createSeries) {
                            {
                                seriesId = null
                                createSeries = false
                                newSeriesTitle = ""
                                sequence = ""
                            }
                        } else null,
                        onAdd = {
                            createSeries = true
                            seriesId = null
                            if (newSeriesTitle.isBlank()) newSeriesTitle = title
                        }
                    ) { seriesId = it.id; createSeries = false }
                }
                if (createSeries) {
                    item {
                        OutlinedTextField(
                            newSeriesTitle,
                            { newSeriesTitle = it },
                            label = { Text("New series name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
                item {
                    EntryField(
                        label = "Pos. in series (optional)",
                        value = sequence,
                        onValueChange = { sequence = it },
                        keyboardType = KeyboardType.Decimal
                    )
                }
            }

            // Collapsible Media Details Section
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMediaDetails = !showMediaDetails }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Media details",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (showMediaDetails) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (showMediaDetails) "Collapse media details" else "Expand media details",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (showMediaDetails) {
                item {
                    StatusButtonGroup(
                        label = "User status",
                        selected = userStatus,
                        options = UserStatus.entries.map { it.value to it.label },
                        onSelected = { userStatus = it }
                    )
                }
                item {
                    StatusButtonGroup(
                        label = "Publishing status",
                        selected = publishingStatus,
                        options = PublishingStatus.values.map { it to it.toDisplayLabel() },
                        onSelected = { publishingStatus = it }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EntryField(
                            label = "Current ${unitName.trim().lowercase()}",
                            value = currentUnit,
                            onValueChange = { newValue ->
                                val sanitized = newValue.replace("-", "")
                                currentUnit = sanitized
                                val parsed = sanitized.toDoubleOrNull() ?: 0.0
                                if (parsed > 0.0 && userStatus == UserStatus.PLANNING.value) {
                                    userStatus = UserStatus.IN_PROGRESS.value
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        EntryField(
                            label = "Unit name",
                            value = unitName,
                            onValueChange = { unitName = it },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Text
                        )
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(isCanon, { isCanon = it })
                        Text("Canon entry")
                    }
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
                        val validCreators = creatorRows.filter {
                            it.authorId != null || (it.createNew && it.newAuthorName.isNotBlank())
                        }
                        val missingNewSeriesTitle = createSeries && newSeriesTitle.isBlank()
                        if (title.isBlank() ||
                            missingNewSeriesTitle ||
                            validCreators.isEmpty() ||
                            (platformId == null && (!createPlatform || newPlatformName.isBlank()))
                        ) {
                            error = if (missingNewSeriesTitle) {
                                "Please enter a name for the new series or clear it."
                            } else {
                                "Title, at least one creator, and platform are required."
                            }
                        } else {
                            onSave(
                                MediaEntryEditorData(
                                    title = title.trim(),
                                    alternativeTitles = altTitles,
                                    type = type,
                                    seriesId = seriesId,
                                    newSeries = if (createSeries && newSeriesTitle.isNotBlank()) {
                                        NewSeriesData(title = newSeriesTitle.trim())
                                    } else null,
                                    creators = validCreators,
                                    platformId = platformId,
                                    newPlatform = if (createPlatform) {
                                        NewPlatformData(
                                            name = newPlatformName,
                                            websiteUrl = newPlatformWebsiteUrl.ifBlank { null },
                                            logoUrl = newPlatformLogoUrl.ifBlank { null }
                                        )
                                    } else null,
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
private fun EntryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true
    )
}

@Composable
private fun StatusButtonGroup(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    compact: Boolean = false,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    modifier = if (compact) Modifier.height(36.dp) else Modifier,
                    label = {
                        if (compact) {
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(text)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun <T> Selector(
    label: String,
    selected: String,
    options: List<T>,
    text: (T) -> String,
    onClear: (() -> Unit)? = null,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onClear != null) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear $label")
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = if (onClear != null) 48.dp else 0.dp)
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