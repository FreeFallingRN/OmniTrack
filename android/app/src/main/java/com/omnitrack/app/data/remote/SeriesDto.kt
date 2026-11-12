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
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String = "novel",
    @SerialName("user_status") val userStatus: String,
    @SerialName("current_unit") val currentUnit: Double = 0.0,
    @SerialName("total_units") val totalUnits: Double? = null,
    @SerialName("unit_name") val unitName: String = "Chapter",
    @SerialName("is_active_target") val isActiveTarget: Boolean = false
)