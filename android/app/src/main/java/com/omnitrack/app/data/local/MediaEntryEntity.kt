package com.omnitrack.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_entries",
    indices = [Index(value = ["seriesId", "type", "sequenceNumber"], unique = true)]
)
data class MediaEntryEntity(
    @PrimaryKey val id: String,
    val seriesId: String?,
    val seriesTitle: String?,
    val title: String,
    val type: String,
    val sequenceNumber: Double?,
    val userStatus: String,
    val publishingStatus: String,
    val isCanon: Boolean,
    val authorNames: String,
    val alternativeTitles: String = "",
    val coverUrl: String?,
    val sourceUrl: String?,
    val platformId: String?,
    val platform: String?,
    val currentUnit: Double,
    val unitName: String,
    val createdAt: String = "",
    val updatedAt: String,
    val seriesAlternativeTitles: String? = null,
    val totalEpisodes: Int? = null,
)