package com.omnitrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: String,
    val title: String,
    @SerialName("clean_title") val cleanTitle: String? = null,
    val type: String,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("current_cumulative_unit") val currentCumulativeUnit: Double,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("unit_name") val unitName: String,
    val rating: Int? = null,
    val notes: String? = null,
    @SerialName("is_active_target") val isActiveTarget: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MediaEntryDto(
    val id: String,
    @SerialName("series_id") val seriesId: String,
    val title: String,
    @SerialName("sequence_number") val sequenceNumber: Double,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("current_unit") val currentUnit: Double,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("start_cumulative_unit") val startCumulativeUnit: Double,
    @SerialName("unit_name") val unitName: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    @SerialName("is_active_target") val isActiveTarget: Boolean,
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
    val type: String,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("unit_name") val unitName: String,
    @SerialName("current_cumulative_unit") val currentCumulativeUnit: Double = 0.0,
    val notes: String? = null,
    val rating: Int? = null,
)

@Serializable
data class CreateEntryRequest(
    @SerialName("series_id") val seriesId: String,
    val title: String,
    @SerialName("sequence_number") val sequenceNumber: Double,
    @SerialName("user_status") val userStatus: String,
    @SerialName("publishing_status") val publishingStatus: String,
    @SerialName("current_unit") val currentUnit: Double = 0.0,
    @SerialName("start_cumulative_unit") val startCumulativeUnit: Double = 0.0,
    @SerialName("unit_name") val unitName: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
)

@Serializable
data class ProgressHistoryRequest(
    @SerialName("series_id") val seriesId: String,
    @SerialName("entry_id") val entryId: String? = null,
    @SerialName("previous_unit") val previousUnit: Double,
    @SerialName("new_unit") val newUnit: Double,
    @SerialName("previous_cumulative_unit") val previousCumulativeUnit: Double? = null,
    @SerialName("new_cumulative_unit") val newCumulativeUnit: Double? = null,
    val source: String
)