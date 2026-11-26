package com.omnitrack.app.domain.model

data class Series(
    val id: String,
    val title: String,
    val cleanTitle: String? = null,
    val rating: Int? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

data class Author(
    val id: String,
    val name: String,
    val nativeName: String? = null,
    val imageUrl: String? = null,
    val bio: String? = null,
)

data class Platform(
    val id: String,
    val name: String,
    val websiteUrl: String? = null,
    val logoUrl: String? = null,
)

data class CreatedMediaEntry(val id: String)

data class CreateSeriesCommand(
    val title: String,
    val rating: Int? = null,
)

data class CreateEntryCommand(
    val seriesId: String? = null,
    val title: String,
    val type: String,
    val sequenceNumber: Double?,
    val userStatus: String,
    val publishingStatus: String = "releasing",
    val currentUnit: Double = 0.0,
    val isCanon: Boolean = true,
    val unitName: String = "Chapter",
    val sourceUrl: String? = null,
    val platformId: String? = null,
    val coverUrl: String? = null,
)

data class UpdateEntryCommand(
    val title: String,
    val type: String,
    val seriesId: String?,
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val platformId: String? = null,
    val userStatus: String,
    val publishingStatus: String,
    val sequenceNumber: Double?,
    val currentUnit: Double,
    val unitName: String,
    val isCanon: Boolean,
)

data class CreatePlatformCommand(
    val name: String,
    val websiteUrl: String? = null,
    val logoUrl: String? = null,
)

data class UpdatePlatformCommand(
    val name: String,
    val websiteUrl: String? = null,
    val logoUrl: String? = null,
)
