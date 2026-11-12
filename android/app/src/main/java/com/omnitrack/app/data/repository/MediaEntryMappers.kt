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
    userStatus = UserStatus.fromValue(userStatus),
    currentUnit = currentUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    isActiveTarget = isActiveTarget
)

fun MediaEntryDto.toEntity(seriesTitle: String?): MediaEntryEntity = MediaEntryEntity(
    id = id,
    seriesId = seriesId,
    seriesTitle = seriesTitle,
    title = title,
    type = type,
    userStatus = userStatus,
    currentUnit = currentUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    isActiveTarget = isActiveTarget
)
