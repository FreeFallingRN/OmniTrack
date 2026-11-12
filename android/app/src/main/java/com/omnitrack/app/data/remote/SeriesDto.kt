package com.omnitrack.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String,
    @SerialName("user_status") val userStatus: String,
    @SerialName("current_cumulative_unit") val currentCumulativeUnit: Double = 0.0,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("unit_name") val unitName: String = "Chapter",
    @SerialName("rating") val rating: Int? = null,
    @SerialName("notes") val notes: String? = "",
    @SerialName("is_active_target") val isActiveTarget: Boolean = false
)

@Serializable
data class SeriesTitleDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String
)

@Serializable
data class MediaEntryDto(
    @SerialName("id") val id: String,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String = "novel",
    @SerialName("user_status") val userStatus: String,
    @SerialName("current_unit") val currentUnit: Double = 0.0,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("start_cumulative_unit") val startCumulativeUnit: Double = 0.0,
    @SerialName("unit_name") val unitName: String = "Chapter",
    @SerialName("is_active_target") val isActiveTarget: Boolean = false
)