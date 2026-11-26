package com.omnitrack.app.data.repository

import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.CreateEntryRequest
import com.omnitrack.app.data.model.CreatePlatformRequest
import com.omnitrack.app.data.model.CreateSeriesRequest
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.data.model.UpdatePlatformRequest
import com.omnitrack.app.domain.model.Author
import com.omnitrack.app.domain.model.CreateEntryCommand
import com.omnitrack.app.domain.model.CreatePlatformCommand
import com.omnitrack.app.domain.model.CreateSeriesCommand
import com.omnitrack.app.domain.model.Platform
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.model.UpdatePlatformCommand

internal fun SeriesDto.toDomain() = Series(id, title, cleanTitle, rating, createdAt, updatedAt)

internal fun AuthorDto.toDomain() = Author(id, name, nativeName, imageUrl, bio)

internal fun PlatformDto.toDomain() = Platform(id, name, websiteUrl, logoUrl)

internal fun CreateSeriesCommand.toDto() = CreateSeriesRequest(title, rating)

internal fun CreatePlatformCommand.toDto() = CreatePlatformRequest(name, websiteUrl, logoUrl)

internal fun UpdatePlatformCommand.toDto() = UpdatePlatformRequest(name, websiteUrl, logoUrl)

internal fun CreateEntryCommand.toDto() = CreateEntryRequest(
    seriesId = seriesId,
    title = title,
    type = type,
    sequenceNumber = sequenceNumber,
    userStatus = userStatus,
    publishingStatus = publishingStatus,
    currentUnit = currentUnit,
    isCanon = isCanon,
    unitName = unitName,
    sourceUrl = sourceUrl,
    platformId = platformId,
    coverUrl = coverUrl,
)
