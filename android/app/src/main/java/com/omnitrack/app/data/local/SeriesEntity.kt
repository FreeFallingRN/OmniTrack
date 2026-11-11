package com.omnitrack.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String,               // 'novel', 'comic', 'anime', 'movie'
    val userStatus: String,         // 'planning', 'in_progress', 'completed', 'paused', 'dropped'
    val currentCumulativeUnit: Double,
    val totalUnits: Double? = null,
    val unitName: String = "Chapter",
    val rating: Int? = null,
    val notes: String = "",
    val isActiveTarget: Boolean = false
)