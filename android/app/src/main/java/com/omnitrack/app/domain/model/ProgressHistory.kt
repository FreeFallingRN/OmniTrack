package com.omnitrack.app.domain.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class ProgressSource(val value: String) {
    DETAIL_SHEET("detail_sheet"),
    INCREMENT("increment"),
    EDITOR("editor"),
    IMPORT("import");

    val isActiveConsumption: Boolean
        get() = this == DETAIL_SHEET || this == INCREMENT

    companion object {
        fun fromValue(value: String): ProgressSource =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: INCREMENT
    }
}

enum class HistoryTimeframe(val label: String) {
    DAYS_7("Week"),
    DAYS_30("Month"),
    YEAR("Year"),
    ALL_TIME("All Time")
}

data class ProgressHistoryItem(
    val entryId: String,
    val activityDate: String,
    val previousUnit: Double,
    val newUnit: Double,
    val source: String = ProgressSource.INCREMENT.value,
    val entryTitle: String = "",
    val mediaType: MediaType = MediaType.NOVEL,
    val unitName: String = "Chapter",
    val coverUrl: String? = null
) {
    fun toEntryLocalDate(): LocalDate? {
        val raw = activityDate.trim()
        if (raw.isEmpty()) return null
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            if (raw.length >= 10) {
                try {
                    LocalDate.parse(raw.substring(0, 10))
                } catch (_: DateTimeParseException) {
                    null
                }
            } else {
                null
            }
        }
    }

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
