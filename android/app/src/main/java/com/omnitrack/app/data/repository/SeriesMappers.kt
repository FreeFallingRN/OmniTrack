package com.omnitrack.app.data.repository

import com.omnitrack.app.data.local.SeriesEntity
import com.omnitrack.app.data.remote.SeriesDto
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.model.UserStatus

fun SeriesEntity.toDomain(): Series = Series(
    id = id,
    title = title,
    type = MediaType.fromValue(type),
    userStatus = UserStatus.fromValue(userStatus),
    currentCumulativeUnit = currentCumulativeUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    rating = rating,
    notes = notes,
    isActiveTarget = isActiveTarget
)

fun Series.toEntity(): SeriesEntity = SeriesEntity(
    id = id,
    title = title,
    type = type.value,
    userStatus = userStatus.value,
    currentCumulativeUnit = currentCumulativeUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    rating = rating,
    notes = notes,
    isActiveTarget = isActiveTarget
)

fun SeriesDto.toDomain(): Series = Series(
    id = id,
    title = title,
    type = MediaType.fromValue(type),
    userStatus = UserStatus.fromValue(userStatus),
    currentCumulativeUnit = currentCumulativeUnit,
    totalUnits = totalUnits,
    unitName = unitName,
    rating = rating,
    notes = notes ?: "",
    isActiveTarget = isActiveTarget
)