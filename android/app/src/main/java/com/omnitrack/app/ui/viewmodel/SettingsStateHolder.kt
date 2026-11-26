package com.omnitrack.app.ui.viewmodel

import android.content.Context
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.OmniTrackPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val progressFolder: String?,
    val progressFolderName: String?,
    val booksFolder: String?,
    val booksFolderName: String?,
    val sortOption: String?,
    val shareStyle: String?,
    val selectedMediaType: String?,
    val selectedUserStatus: String?,
    val revision: Long
)

class SettingsStateHolder(context: Context) {
    private val preferences = OmniTrackPreferences(context)
    private fun snapshot(revision: Long) = SettingsState(
        progressFolder = preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolder),
        progressFolderName = preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolderName),
        booksFolder = preferences.getString(OmniTrackPreferenceKeys.MoonReaderBooksFolder),
        booksFolderName = preferences.getString(OmniTrackPreferenceKeys.MoonReaderBooksFolderName),
        sortOption = preferences.getString(OmniTrackPreferenceKeys.SortOption, "last_updated"),
        shareStyle = preferences.getString(OmniTrackPreferenceKeys.ShareFormatStyle),
        selectedMediaType = preferences.getString(OmniTrackPreferenceKeys.SelectedMediaType),
        selectedUserStatus = preferences.getString(OmniTrackPreferenceKeys.SelectedUserStatus),
        revision = revision
    )
    private val _state = MutableStateFlow(snapshot(0))
    val state = _state.asStateFlow()
    private val listener = preferences.addListener { _state.value = snapshot(_state.value.revision + 1) }

    fun close() = preferences.removeListener(listener)

    fun linkedFileUri(entryId: String): String? =
        preferences.getString(OmniTrackPreferenceKeys.moonReaderFileUri(entryId))

    fun linkedFileName(entryId: String): String? =
        preferences.getString(OmniTrackPreferenceKeys.moonReaderFileName(entryId))

    fun chapterOffset(entryId: String): Double? {
        val key = OmniTrackPreferenceKeys.moonReaderChapterOffset(entryId)
        return if (preferences.contains(key)) Double.fromBits(preferences.getLong(key)) else null
    }

    fun setSortOption(value: String) {
        preferences.edit().putString(OmniTrackPreferenceKeys.SortOption, value).apply()
    }

    fun setShareStyle(value: String) {
        preferences.edit().putString(OmniTrackPreferenceKeys.ShareFormatStyle, value).apply()
    }

    fun setSelectedMediaType(value: String) {
        preferences.edit().putString(OmniTrackPreferenceKeys.SelectedMediaType, value).apply()
    }

    fun setSelectedUserStatus(value: String) {
        preferences.edit().putString(OmniTrackPreferenceKeys.SelectedUserStatus, value).apply()
    }

    fun setProgressFolderName(name: String) {
        check(preferences.edit().putString(OmniTrackPreferenceKeys.MoonReaderFolderName, name).commit()) {
            "Could not save the folder name"
        }
    }

    fun setProgressFolder(folder: String, name: String) {
        check(preferences.edit().putString(OmniTrackPreferenceKeys.MoonReaderFolder, folder)
            .putString(OmniTrackPreferenceKeys.MoonReaderFolderName, name).commit()) {
            "Could not save the selected folder"
        }
    }

    fun setBooksFolder(folder: String, name: String) {
        check(preferences.edit().putString(OmniTrackPreferenceKeys.MoonReaderBooksFolder, folder)
            .putString(OmniTrackPreferenceKeys.MoonReaderBooksFolderName, name).commit()) {
            "Could not save the selected books folder"
        }
    }
}
