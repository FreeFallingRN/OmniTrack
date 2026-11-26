package com.omnitrack.app.ui.components

import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import java.math.BigDecimal
import java.math.RoundingMode

internal fun normalizeTitleKey(raw: String): String =
    raw.lowercase()
        .replace(Regex("""@\s*comic\b|\((?:novel|manga|comic|webtoon|light novel|ln|wn)\)|:\s*season\s*\d+|season\s*\d+"""), "")
        .filter { it.isLetterOrDigit() }

internal fun isSimilarTitle(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    if (a.length < 4 || b.length < 4) return false
    val shorter = if (a.length <= b.length) a else b
    val longer = if (a.length > b.length) a else b
    return shorter.length >= 8 && longer.contains(shorter) && shorter.length.toDouble() / longer.length >= 0.65
}

internal fun findSimilarEntries(
    title: String,
    altTitles: List<String>,
    newAltTitle: String,
    sourceUrl: String,
    type: MediaType,
    entries: List<MediaEntry>,
    existingId: String?
): List<Pair<MediaEntry, Boolean>> {
    val cleanUrl = sourceUrl.trim().trimEnd('/').lowercase()
    val candidateKeys = (listOf(title, newAltTitle) + altTitles)
        .map(::normalizeTitleKey)
        .filter { it.isNotEmpty() }
        .distinct()
    return entries.filter { it.type == type && it.id != existingId }.mapNotNull { candidate ->
        val urlMatch = cleanUrl.isNotBlank() &&
            !candidate.sourceUrl.isNullOrBlank() &&
            candidate.sourceUrl.trim().trimEnd('/').lowercase() == cleanUrl
        val existingKeys = (listOf(candidate.title) + candidate.allAlternativeTitles)
            .map(::normalizeTitleKey)
            .filter { it.isNotEmpty() }
        val titleMatch = existingKeys.any { ex -> candidateKeys.any { cand -> isSimilarTitle(ex, cand) } }
        val seriesMatch = candidate.seriesTitle?.let(::normalizeTitleKey)
            ?.let { seriesKey -> candidateKeys.any { isSimilarTitle(seriesKey, it) } } == true
        if (urlMatch || titleMatch || seriesMatch) candidate to (urlMatch || titleMatch) else null
    }
}

internal fun appendAlias(aliases: List<String>, title: String, alias: String): List<String> {
    val cleaned = alias.trim()
    return if (cleaned.isNotBlank() && !cleaned.equals(title.trim(), ignoreCase = true) &&
        aliases.none { it.trim().equals(cleaned, ignoreCase = true) }
    ) aliases + cleaned else aliases
}

internal fun aliasesAfterMainTitleChange(aliases: List<String>, previousTitle: String, newTitle: String): List<String> =
    (aliases + previousTitle.trim())
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals(newTitle.trim(), ignoreCase = true) }
        .distinctBy { it.lowercase() }

internal fun mergeEntryAliases(target: MediaEntry, title: String, aliases: List<String>): List<String> =
    (target.alternativeTitles + title + aliases)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals(target.title.trim(), ignoreCase = true) }
        .distinctBy { it.lowercase() }

internal fun mergeEntryCreators(
    existing: List<Pair<String, String>>,
    incoming: List<Pair<String, String>>,
    authorNames: Map<String, String>
): List<Pair<String, String>> =
    (existing + incoming).distinctBy { (id, role) ->
        (authorNames[id] ?: id) to role.trim().lowercase().ifBlank { "author" }
    }

internal fun mergeCreatorDraft(
    rows: List<CreatorFormRow>,
    draft: CreatorFormRow,
    editingIndex: Int?
): List<CreatorFormRow> =
    if (draft.authorId != null || (draft.createNew && draft.newAuthorName.isNotBlank())) {
        if (editingIndex == null) rows + draft
        else rows.mapIndexed { index, row -> if (index == editingIndex) draft else row }
    } else rows

internal fun creatorRowsForNames(creators: List<Pair<String, String>>, authors: List<AuthorDto>): List<CreatorFormRow> =
    creators.map { (rawName, role) ->
        val matched = authors.firstOrNull { it.name.trim().equals(rawName.trim(), ignoreCase = true) }
        if (matched != null) CreatorFormRow(authorId = matched.id, role = role)
        else CreatorFormRow(createNew = true, newAuthorName = rawName.trim(), role = role)
    }

internal fun validateEntryDraft(
    creatorDraft: CreatorFormRow,
    creators: List<CreatorFormRow>,
    publishingStatus: String,
    mergeTarget: MediaEntry?,
    groupWithEntry: MediaEntry?,
    entries: List<MediaEntry>,
    type: MediaType,
    title: String,
    createSeries: Boolean,
    newSeriesTitle: String,
    platformId: String?,
    createPlatform: Boolean,
    newPlatformName: String,
    positionError: String?
): String? {
    val missingNewSeriesTitle = createSeries && newSeriesTitle.isBlank()
    return when {
        creatorDraft.createNew && creatorDraft.newAuthorName.isBlank() ->
            "Please enter a creator name or choose an existing creator."
        publishingStatus.isBlank() ->
            "Publishing status was not available from the source. Please choose a status."
        mergeTarget != null && entries.none { it.id == mergeTarget.id && it.type == type } ->
            "The selected merge target is no longer available for this media type."
        groupWithEntry != null && entries.none { it.id == groupWithEntry.id && it.type == type } ->
            "The selected series entry is no longer available for this media type."
        title.isBlank() || (mergeTarget == null && missingNewSeriesTitle) ||
            (creators.isEmpty() && mergeTarget?.creators.isNullOrEmpty() && mergeTarget?.authors.isNullOrEmpty()) ||
            (mergeTarget?.platformId == null && platformId == null && (!createPlatform || newPlatformName.isBlank())) ->
            if (missingNewSeriesTitle) "Please enter a name for the new series or clear it."
            else "Title, at least one creator, and platform are required."
        mergeTarget == null && positionError != null -> positionError
        else -> null
    }
}

internal fun entryPositionError(
    sequence: String,
    seriesId: String?,
    targetEntryId: String?,
    groupWithEntry: MediaEntry?,
    createSeries: Boolean,
    type: MediaType,
    entries: List<MediaEntry>
): String? {
    val position = sequence.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
        BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
    val groupedEntry = groupWithEntry?.let { selected ->
        entries.firstOrNull { it.id == selected.id && it.seriesId == null }
    }?.takeIf { seriesId != null || createSeries }
    val groupedPosition = groupedEntry?.sequenceNumber
    val positionConflict = if (seriesId != null && position != null) {
        entries.firstOrNull {
            it.seriesId == seriesId && it.type == type && it.id != targetEntryId &&
                it.sequenceNumber == position
        }
    } else null
    val groupedPositionConflict = if (seriesId != null && groupedPosition != null) {
        entries.firstOrNull {
            it.seriesId == seriesId && it.type == groupedEntry?.type &&
                it.id != targetEntryId && it.sequenceNumber == groupedPosition
        }
    } else null
    return when {
        positionConflict != null -> "This position is already used by \"${positionConflict.title}\"."
        position != null && groupedPosition == position ->
            "This position will be used by \"${groupedEntry.title}\" when it is added to the series."
        groupedPositionConflict != null ->
            "\"${groupedEntry?.title}\" cannot be added: its position is already used by \"${groupedPositionConflict.title}\"."
        else -> null
    }
}
