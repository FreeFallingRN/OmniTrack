package com.omnitrack.app.ui.library.editor

import com.omnitrack.app.data.AppLogger
import com.omnitrack.app.domain.model.CreateEntryCommand
import com.omnitrack.app.domain.model.CreatePlatformCommand
import com.omnitrack.app.domain.model.CreateSeriesCommand
import com.omnitrack.app.domain.model.UpdateEntryCommand
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.ui.viewmodel.MangaBakaOwner
import kotlinx.coroutines.CancellationException

internal suspend fun saveMediaEntry(
    viewModel: SeriesViewModel,
    mangaBaka: MangaBakaOwner,
    form: MediaEntryEditorData,
    editorEntry: MediaEntry?,
    mediaEntries: List<MediaEntry>,
    importedMangaBakaId: Long?,
    mangaBakaLinkUrl: String?,
    onCreated: (String) -> Unit
) = try {
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
    val authors = viewModel.getAuthors()
    val knownAuthors = authors.associate { it.name.trim().lowercase() to it.id }.toMutableMap()
    val authorNames = authors.associate { it.id to it.name.trim().lowercase() }.toMutableMap()

    fun savedCreators(entry: MediaEntry): List<Pair<String, String>> {
        val creators = entry.creators.ifEmpty { entry.authors.map { EntryCreator(name = it) } }
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
        !form.newSeries?.title.isNullOrBlank() ->
            viewModel.createSeries(CreateSeriesCommand(title = form.newSeries.title.trim())).id
        else -> null
    }

    val importedCreatorIds = mutableMapOf<String, String>()
    val resolvedCreators: List<Pair<String, String>> = form.creators.map { row ->
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
        checkNotNull(id) { "Could not resolve creator." } to row.role
    }
    val resolvedPlatformId = mergeTarget?.platformId ?: form.platformId ?: viewModel.createPlatform(
        CreatePlatformCommand(
            name = form.newPlatform!!.name,
            websiteUrl = form.newPlatform.websiteUrl,
            logoUrl = form.newPlatform.logoUrl
        )
    ).id

    form.linkExistingEntryToSeriesId?.let {
        val existingMatch = groupedMatch
        if (existingMatch != null && existingMatch.seriesId == null && resolvedSeriesId != null) {
            viewModel.updateMediaEntry(
                id = existingMatch.id,
                request = UpdateEntryCommand(
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

    val targetUpdateId = editorEntry?.id ?: mergeTarget?.id
    if (targetUpdateId == null) {
        val created = viewModel.createMediaEntry(
            CreateEntryCommand(
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
        onCreated(created.id)
        if (importedMangaBakaId != null && mangaBakaLinkUrl != null) {
            mangaBaka.linkImportedMangaBakaEntry(importedMangaBakaId, created.id, form.unitName, mangaBakaLinkUrl)
        }
    } else {
        val mergedAliases = mergeTarget?.let { mergeEntryAliases(it, form.title, form.alternativeTitles) }
        viewModel.updateMediaEntry(
            id = targetUpdateId,
            request = UpdateEntryCommand(
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
            creators = mergeEntryCreators(existingCreators, resolvedCreators, authorNames),
            alternativeTitles = mergedAliases ?: form.alternativeTitles
        )
        if (importedMangaBakaId != null && mangaBakaLinkUrl != null) {
            mangaBaka.linkImportedMangaBakaEntry(
                importedMangaBakaId, targetUpdateId, mergeTarget?.unitName ?: form.unitName, mangaBakaLinkUrl
            )
        }
    }
    AppLogger.info("[MediaEntrySave] Entry save completed")
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLogger.error("[MediaEntrySave] Entry save failed", e)
    throw e
}
