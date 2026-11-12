package com.omnitrack.app.domain.model

data class MediaEntry(
    val id: String,
    val seriesId: String?,
    val seriesTitle: String?,
    val title: String,
    val type: MediaType,
    val userStatus: UserStatus,
    val currentUnit: Double,
    val totalUnits: Double? = null,
    val unitName: String = "Chapter",
    val isActiveTarget: Boolean = false
)
