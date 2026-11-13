package com.omnitrack.app.data.repository

import com.omnitrack.app.data.local.MediaEntryEntity
import com.omnitrack.app.data.remote.MediaEntryDto
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus

fun MediaEntryEntity.toDomain(): MediaEntry {
    val parsedCreators = authorNames.split("|")
        .filter { it.isNotBlank() }
        .map { raw ->
            val parts = raw.split("::")
            when (parts.size) {
                3 -> EntryCreator(id = parts[0].ifBlank { null }, name = parts[1], role = parts[2])
                2 -> EntryCreator(name = parts[0], role = parts[1])
                else -> EntryCreator(name = raw, role = "author")
            }
        }

    return MediaEntry(
        id = id,
        seriesId = seriesId,
        seriesTitle = seriesTitle,
        title = title,
        type = MediaType.fromValue(type),
        sequenceNumber = sequenceNumber,
        userStatus = UserStatus.fromValue(userStatus),
        publishingStatus = publishingStatus,
        isCanon = isCanon,
        authors = parsedCreators.map { it.name },
        creators = parsedCreators,
        alternativeTitles = alternativeTitles.split("|").filter { it.isNotBlank() },
        coverUrl = coverUrl,
        sourceUrl = sourceUrl,
        platformId = platformId,
        platform = platform,
        currentUnit = currentUnit,
        unitName = unitName,
        updatedAt = updatedAt,
    )
}

fun MediaEntryDto.toEntity(
    seriesTitle: String?,
    creators: List<EntryCreator>,
    altTitles: List<String>
): MediaEntryEntity = MediaEntryEntity(
    id = id,
    seriesId = seriesId,
    seriesTitle = seriesTitle,
    title = title,
    type = type,
    sequenceNumber = sequenceNumber,
    userStatus = userStatus,
    publishingStatus = publishingStatus,
    isCanon = isCanon,
    authorNames = creators.joinToString("|") { "${it.id.orEmpty()}::${it.name}::${it.role}" },
    alternativeTitles = altTitles.distinct().joinToString("|"),
    coverUrl = coverUrl,
    sourceUrl = sourceUrl,
    platformId = platformId,
    platform = platform?.name,
    currentUnit = currentUnit,
    unitName = unitName,
    updatedAt = updatedAt,
)