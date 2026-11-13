package com.omnitrack.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesTitleDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String
)

@Serializable
data class MediaEntryDto(
    @SerialName("id") val id: String,
    @SerialName("series_id") val seriesId: String,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String = "novel",
    @SerialName("sequence_number") val sequenceNumber: Double = 1.0,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String = "releasing",
    @SerialName("is_canon") val isCanon: Boolean = true,
    @SerialName("current_unit") val currentUnit: Double = 0.0,
    @SerialName("unit_name") val unitName: String = "Chapter",
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("platform_id") val platformId: String? = null,
    val platform: PlatformRefDto? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PlatformRefDto(
    val id: String,
    val name: String
)

@Serializable
data class EntryAuthorDto(
    @SerialName("entry_id") val entryId: String,
    @SerialName("author_id") val authorId: String
)

@Serializable
data class AuthorDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String
)