package com.omnitrack.app.data.repository

import com.omnitrack.app.data.local.MediaEntryEntity
import com.omnitrack.app.data.model.MediaEntryDto
import com.omnitrack.app.domain.model.EntryCreator
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun MediaEntryEntity.toDomain(): MediaEntry {
    val parsedCreators = decodeCreators(authorNames)

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
        alternativeTitles = if (seriesId != null && seriesAlternativeTitles == null) {
            emptyList()
        } else {
            decodeAlternativeTitles(alternativeTitles)
        },
        coverUrl = coverUrl,
        sourceUrl = sourceUrl,
        platformId = platformId,
        platform = platform,
        currentUnit = currentUnit,
        unitName = unitName,
        createdAt = createdAt.ifBlank { updatedAt },
        updatedAt = updatedAt,
        seriesAlternativeTitles = decodeAlternativeTitles(
            seriesAlternativeTitles ?: if (seriesId != null) alternativeTitles else ""
        ),
        aliasScopeUnverified = seriesId != null && seriesAlternativeTitles == null,
    )
}

private fun decodeCreators(value: String): List<EntryCreator> {
    val json = runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull()
    if (json != null) {
        return json.mapNotNull { element ->
            val fields = element as? JsonObject ?: return@mapNotNull null
            val name = (fields["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            EntryCreator(
                id = (fields["id"] as? JsonPrimitive)?.contentOrNull,
                name = name,
                role = (fields["role"] as? JsonPrimitive)?.contentOrNull ?: "author"
            )
        }
    }

    return value.split("|")
        .filter { it.isNotBlank() }
        .map { raw ->
            val parts = raw.split("::")
            when (parts.size) {
                3 -> EntryCreator(id = parts[0].ifBlank { null }, name = parts[1], role = parts[2])
                2 -> EntryCreator(name = parts[0], role = parts[1])
                else -> EntryCreator(name = raw, role = "author")
            }
        }
}

private fun decodeAlternativeTitles(value: String): List<String> {
    val json = runCatching { Json.decodeFromString<List<String>>(value) }.getOrNull()
    return json ?: value.split("|").filter { it.isNotBlank() }
}

fun MediaEntryDto.toEntity(
    seriesTitle: String?,
    creators: List<EntryCreator>,
    altTitles: List<String>,
    seriesAltTitles: List<String>
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
    authorNames = JsonArray(creators.map { creator ->
        buildJsonObject {
            put("id", creator.id?.let(::JsonPrimitive) ?: JsonNull)
            put("name", creator.name)
            put("role", creator.role)
        }
    }).toString(),
    alternativeTitles = Json.encodeToString(altTitles.distinct()),
    coverUrl = coverUrl,
    sourceUrl = sourceUrl,
    platformId = platformId,
    platform = platform?.name,
    currentUnit = currentUnit,
    unitName = unitName,
    createdAt = createdAt.ifBlank { updatedAt },
    updatedAt = updatedAt,
    seriesAlternativeTitles = Json.encodeToString(seriesAltTitles.distinct()),
)