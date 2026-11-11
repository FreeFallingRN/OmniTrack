package com.omnitrack.app.domain.model

enum class MediaType(val value: String, val label: String) {
    NOVEL("novel", "Novels"),
    COMIC("comic", "Comics"),
    ANIME("anime", "Anime"),
    MOVIE("movie", "Movies");

    companion object {
        fun fromValue(value: String): MediaType {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: NOVEL
        }
    }
}

enum class UserStatus(val value: String, val label: String) {
    PLANNING("planning", "Planning"),
    IN_PROGRESS("in_progress", "In Progress"),
    COMPLETED("completed", "Completed"),
    PAUSED("paused", "Paused"),
    DROPPED("dropped", "Dropped");

    companion object {
        fun fromValue(value: String): UserStatus {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: IN_PROGRESS
        }
    }
}

data class Series(
    val id: String,
    val title: String,
    val type: MediaType,
    val userStatus: UserStatus,
    val currentCumulativeUnit: Double,
    val totalUnits: Double? = null,
    val unitName: String = "Chapter",
    val rating: Int? = null,
    val notes: String = "",
    val isActiveTarget: Boolean = false
)