package com.omnitrack.app.domain.model

data class MediaEntry(
    val id: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val title: String,
    val type: MediaType,
    val sequenceNumber: Double? = null,
    val userStatus: UserStatus,
    val publishingStatus: String = "releasing",
    val isCanon: Boolean = true,
    val authors: List<String> = emptyList(),
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val platformId: String? = null,
    val platform: String? = null,
    val currentUnit: Double,
    val unitName: String = "Chapter",
    val updatedAt: String,
)
