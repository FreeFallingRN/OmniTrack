package com.omnitrack.app.data

import android.content.Context
import android.content.SharedPreferences

sealed class PreferenceKey<T> protected constructor(internal val name: String) {
    @ConsistentCopyVisibility
    data class StringKey internal constructor(private val key: String) : PreferenceKey<String>(key)

    @ConsistentCopyVisibility
    data class LongKey internal constructor(private val key: String) : PreferenceKey<Long>(key)

    @ConsistentCopyVisibility
    data class IntKey internal constructor(private val key: String) : PreferenceKey<Int>(key)
}

object OmniTrackPreferenceKeys {
    val SortOption = PreferenceKey.StringKey("sort_option")
    val SortDirection = PreferenceKey.StringKey("sort_direction")
    val PublishingStatusFilter = PreferenceKey.StringKey("publishing_status_filter")
    val ShareFormatStyle = PreferenceKey.StringKey("share_format_style")
    val SelectedMediaType = PreferenceKey.StringKey("selected_media_type")
    val SelectedUserStatus = PreferenceKey.StringKey("selected_user_status")
    val MoonReaderFolder = PreferenceKey.StringKey("moon_reader_folder")
    val MoonReaderFolderName = PreferenceKey.StringKey("moon_reader_folder_name")
    val MoonReaderBooksFolder = PreferenceKey.StringKey("moon_reader_books_folder")
    val MoonReaderBooksFolderName = PreferenceKey.StringKey("moon_reader_books_folder_name")

    fun moonReaderFileUri(entryId: String) =
        PreferenceKey.StringKey("moon_reader_file_uri_$entryId")

    fun moonReaderFileName(entryId: String) =
        PreferenceKey.StringKey("moon_reader_file_name_$entryId")

    fun moonReaderChapterOffset(entryId: String) =
        PreferenceKey.LongKey("moon_reader_chapter_offset_$entryId")

    fun moonReaderSpineOffset(entryId: String) =
        PreferenceKey.LongKey("moon_reader_spine_offset_$entryId")

    fun moonReaderTimestamp(entryId: String) =
        PreferenceKey.LongKey("moon_reader_timestamp_$entryId")

    fun moonReaderSpine(entryId: String) =
        PreferenceKey.IntKey("moon_reader_spine_$entryId")

    fun moonReaderLastModified(entryId: String) =
        PreferenceKey.LongKey("moon_reader_last_modified_$entryId")

    internal fun fromName(name: String): PreferenceChange = when {
        name == MoonReaderFolder.name -> PreferenceChange.MOON_READER_FOLDER
        name == MoonReaderFolderName.name -> PreferenceChange.MOON_READER_FOLDER_NAME
        name == MoonReaderBooksFolder.name -> PreferenceChange.MOON_READER_BOOKS_FOLDER
        name == MoonReaderBooksFolderName.name -> PreferenceChange.MOON_READER_BOOKS_FOLDER_NAME
        name.startsWith("moon_reader_file_uri_") -> PreferenceChange.MOON_READER_FILE_URI
        name.startsWith("moon_reader_chapter_offset_") -> PreferenceChange.MOON_READER_CHAPTER_OFFSET
        else -> PreferenceChange.OTHER
    }
}

enum class PreferenceChange {
    ALL,
    MOON_READER_FOLDER,
    MOON_READER_FOLDER_NAME,
    MOON_READER_BOOKS_FOLDER,
    MOON_READER_BOOKS_FOLDER_NAME,
    MOON_READER_FILE_URI,
    MOON_READER_CHAPTER_OFFSET,
    OTHER
}

class OmniTrackPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getString(key: PreferenceKey.StringKey, defaultValue: String? = null): String? =
        preferences.getString(key.name, defaultValue)

    fun getLong(key: PreferenceKey.LongKey, defaultValue: Long = 0L): Long =
        preferences.getLong(key.name, defaultValue)

    fun getInt(key: PreferenceKey.IntKey, defaultValue: Int = 0): Int =
        preferences.getInt(key.name, defaultValue)

    fun contains(key: PreferenceKey<*>): Boolean = preferences.contains(key.name)

    fun moonReaderLinkedEntryIds(): Set<String> =
        preferences.all.keys.mapNotNull { name ->
            when {
                name.startsWith("moon_reader_file_uri_") -> name.removePrefix("moon_reader_file_uri_")
                name.startsWith("moon_reader_file_name_") -> name.removePrefix("moon_reader_file_name_")
                else -> null
            }
        }.toSet()

    fun unlinkMoonReader(entryId: String) {
        check(edit()
            .remove(OmniTrackPreferenceKeys.moonReaderFileUri(entryId))
            .remove(OmniTrackPreferenceKeys.moonReaderFileName(entryId))
            .remove(OmniTrackPreferenceKeys.moonReaderChapterOffset(entryId))
            .remove(OmniTrackPreferenceKeys.moonReaderSpineOffset(entryId))
            .remove(OmniTrackPreferenceKeys.moonReaderTimestamp(entryId))
            .remove(OmniTrackPreferenceKeys.moonReaderSpine(entryId))
            .remove(OmniTrackPreferenceKeys.moonReaderLastModified(entryId))
            .commit()) { "Could not unlink Moon+ Reader book" }
    }

    fun edit(): Editor = Editor(preferences.edit())

    fun addListener(onChange: (PreferenceChange) -> Unit): Listener {
        val sharedListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            onChange(key?.let { OmniTrackPreferenceKeys.fromName(it) } ?: PreferenceChange.ALL)
        }
        preferences.registerOnSharedPreferenceChangeListener(sharedListener)
        return Listener(sharedListener)
    }

    fun removeListener(listener: Listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener.sharedListener)
    }

    class Listener internal constructor(internal val sharedListener: SharedPreferences.OnSharedPreferenceChangeListener)

    class Editor internal constructor(private val editor: SharedPreferences.Editor) {
        fun putString(key: PreferenceKey.StringKey, value: String?): Editor = apply {
            editor.putString(key.name, value)
        }

        fun putLong(key: PreferenceKey.LongKey, value: Long): Editor = apply {
            editor.putLong(key.name, value)
        }

        fun putInt(key: PreferenceKey.IntKey, value: Int): Editor = apply {
            editor.putInt(key.name, value)
        }

        fun remove(key: PreferenceKey<*>): Editor = apply { editor.remove(key.name) }

        fun apply() = editor.apply()

        fun commit(): Boolean = editor.commit()
    }

    companion object {
        const val PREFERENCES_NAME = "omnitrack_prefs"
    }
}
