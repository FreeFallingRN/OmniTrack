package com.omnitrack.app.utils

enum class LibrarySortOption(val label: String, val key: String) {
    LAST_UPDATED("Last Updated", "last_updated"),
    ALPHABETICAL("Alphabetical (A-Z)", "alphabetical"),
    READ_CHAPTERS("Read Chapters / Progress", "read_chapters");

    companion object {
        fun fromKey(key: String?): LibrarySortOption {
            return entries.find { it.key == key } ?: LAST_UPDATED
        }
    }
}