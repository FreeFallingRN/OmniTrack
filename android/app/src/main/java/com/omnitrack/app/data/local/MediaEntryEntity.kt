package com.omnitrack.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_entries")
data class MediaEntryEntity(
    @PrimaryKey val id: String,
    val seriesId: String?,
    val seriesTitle: String?,
    val title: String,
    val type: String,
    val sequenceNumber: Double,
    val userStatus: String,
    val publishingStatus: String,
    val isCanon: Boolean,
    val authorNames: String,
    val coverUrl: String?,
    val sourceUrl: String?,
    val platformId: String?,
    val platform: String?,
    val currentUnit: Double,
    val unitName: String,
)
