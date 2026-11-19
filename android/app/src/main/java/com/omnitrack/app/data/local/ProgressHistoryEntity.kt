package com.omnitrack.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "progress_history",
    primaryKeys = ["entryId", "activityDate"],
    indices = [Index(value = ["activityDate"])]
)
data class ProgressHistoryEntity(
    val entryId: String,
    val activityDate: String,
    val previousUnit: Double,
    val newUnit: Double,
    val source: String = "android_app",
    val updatedAt: String = ""
)

