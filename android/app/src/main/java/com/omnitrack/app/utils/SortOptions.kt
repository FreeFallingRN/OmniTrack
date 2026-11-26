package com.omnitrack.app.utils

enum class LibrarySortOption(
    val label: String,
    val key: String,
    val dialogLabel: String,
    val defaultDescending: Boolean
) {
    LAST_UPDATED("Last Updated", "last_updated", "Recently updated", true),
    ALPHABETICAL("Alphabetical (A-Z)", "alphabetical", "Title", false),
    READ_CHAPTERS("Read Chapters / Progress", "read_chapters", "Progress", true),
    DATE_ADDED("Date added", "date_added", "Date added", true);

    companion object {
        fun fromKey(key: String?): LibrarySortOption {
            return entries.find { it.key == key } ?: LAST_UPDATED
        }
    }
}