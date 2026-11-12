package com.omnitrack.app.data.repository

import com.omnitrack.app.data.local.MediaEntryEntity
import com.omnitrack.app.data.remote.MediaEntryDto
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus

fun MediaEntryEntity.toDomain(): MediaEntry = MediaEntry(
    id = id,
    seriesId = seriesId,
    seriesTitle = seriesTitle,
    title = title,
    type = MediaType.fromValue(type),
    sequenceNumber = sequenceNumber,
    userStatus = UserStatus.fromValue(userStatus),
    publishingStatus = publishingStatus,
    isCanon = isCanon,
    authors = authorNames.split("|").filter { it.isNotBlank() },
    coverUrl = coverUrl,
    sourceUrl = sourceUrl,
    platform = platform,
    currentUnit = currentUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    isActiveTarget = isActiveTarget
)

fun MediaEntryDto.toEntity(
    seriesTitle: String?,
    authorNames: List<String>
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
    authorNames = authorNames.joinToString("|"),
    coverUrl = coverUrl,
    sourceUrl = sourceUrl,
    platform = platform,
    currentUnit = currentUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    isActiveTarget = isActiveTarget
)
