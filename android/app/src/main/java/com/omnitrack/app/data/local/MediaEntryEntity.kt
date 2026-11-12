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
    val userStatus: String,
    val currentUnit: Double,
    val totalUnits: Double?,
    val startCumulativeUnit: Double,
    val unitName: String,
    val isActiveTarget: Boolean
)
