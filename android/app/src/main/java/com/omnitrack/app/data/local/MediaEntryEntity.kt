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
    val isCanon: Boolean,
    val authorNames: String,
    val coverUrl: String?,
    val currentUnit: Double,
    val totalUnits: Double?,
    val unitName: String,
    val isActiveTarget: Boolean
)
