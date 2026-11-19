package com.omnitrack.app.domain.model

data class ProgressHistoryItem(
    val entryId: String,
    val activityDate: String,
    val previousUnit: Double,
    val newUnit: Double,
    val source: String = "android_app",
    val entryTitle: String = "",
    val mediaType: MediaType = MediaType.NOVEL,
    val unitName: String = "Chapter",
    val coverUrl: String? = null
) {
    val delta: Double get() = (newUnit - previousUnit).coerceAtLeast(0.0)
}

data class DailyProgressStats(
    val date: String,
    val totalUnitsDelta: Double,
    val entriesCount: Int
)

data class MediaTypeStats(
    val type: MediaType,
    val totalUnits: Double,
    val entriesCount: Int
)

data class OverallProgressStats(
    val totalUnitsTracked: Double,
    val activeDaysCount: Int,
    val currentStreakDays: Int,
    val mediaTypeBreakdown: List<MediaTypeStats>
)

