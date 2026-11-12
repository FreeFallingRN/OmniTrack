package com.omnitrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: String,
    val title: String,
    @SerialName("clean_title") val cleanTitle: String? = null,
    val rating: Int? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MediaEntryDto(
    val id: String,
    @SerialName("series_id") val seriesId: String,
    val title: String,
    val type: String = "novel",
    @SerialName("sequence_number") val sequenceNumber: Double,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("current_unit") val currentUnit: Double,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("is_canon") val isCanon: Boolean = true,
    @SerialName("unit_name") val unitName: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    @SerialName("is_active_target") val isActiveTarget: Boolean,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ExternalLinkDto(
    val id: Long,
    @SerialName("series_id") val seriesId: String,
    @SerialName("entry_id") val entryId: String? = null,
    val platform: String,
    @SerialName("display_name") val displayName: String,
    val url: String,
    @SerialName("icon_key") val iconKey: String,
    @SerialName("is_primary") val isPrimary: Boolean,
)

@Serializable
data class CreateSeriesRequest(
    val title: String,
    val notes: String? = null,
    val rating: Int? = null,
)

@Serializable
data class UpdateSeriesRequest(
    val title: String,
    val notes: String? = null,
    val rating: Int? = null,
)

@Serializable
data class CreateEntryRequest(
    @SerialName("series_id") val seriesId: String,
    val title: String,
    val type: String,
    @SerialName("sequence_number") val sequenceNumber: Double,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("current_unit") val currentUnit: Double = 0.0,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("is_canon") val isCanon: Boolean = true,
    @SerialName("unit_name") val unitName: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
data class UpdateEntryRequest(
    val title: String,
    val type: String,
    @SerialName("series_id") val seriesId: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("sequence_number") val sequenceNumber: Double,
    @SerialName("current_unit") val currentUnit: Double,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("unit_name") val unitName: String,
    @SerialName("is_canon") val isCanon: Boolean,
)

@Serializable
data class AuthorDto(
    val id: String,
    val name: String,
    @SerialName("native_name") val nativeName: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val bio: String? = null,
)

@Serializable
data class EntryAuthorDto(
    @SerialName("entry_id") val entryId: String,
    @SerialName("author_id") val authorId: String,
    val role: String = "author",
)

@Serializable
data class ProgressHistoryRequest(
    @SerialName("entry_id") val entryId: String,
    @SerialName("activity_date") val activityDate: String,
    @SerialName("previous_unit") val previousUnit: Double,
    @SerialName("new_unit") val newUnit: Double,
    val source: String
)