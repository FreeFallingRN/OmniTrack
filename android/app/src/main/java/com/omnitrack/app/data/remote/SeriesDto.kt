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
    @SerialName("is_canon") val isCanon: Boolean = true,
    @SerialName("current_unit") val currentUnit: Double = 0.0,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("unit_name") val unitName: String = "Chapter",
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_active_target") val isActiveTarget: Boolean = false
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