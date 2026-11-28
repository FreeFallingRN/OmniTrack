package com.omnitrack.app.ui.viewmodel

import android.content.Context
import com.omnitrack.app.BuildConfig
import com.omnitrack.app.data.AppLogger
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.LokiLogPush
import com.omnitrack.app.data.TrackerSyncDiagnosticLog
import com.omnitrack.app.domain.model.HistoryTimeframe
import com.omnitrack.app.domain.model.PublishingStatus
import com.omnitrack.app.domain.model.ReleaseChannel
import com.omnitrack.app.utils.LibrarySortOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LokiTokenStatus(val configured: Boolean, val hasOverride: Boolean, val error: String? = null)

data class SettingsState(
    val progressFolder: String?,
    val progressFolderName: String?,
    val booksFolder: String?,
    val booksFolderName: String?,
    val sortOption: LibrarySortOption,
    val sortDescending: Boolean,
    val selectedPublishingStatus: String?,
    val shareStyle: String?,
    val selectedMediaType: String?,
    val selectedUserStatus: String?,
    val selectedStatsPeriod: HistoryTimeframe,
    val releaseChannel: ReleaseChannel,
    val liveLoggingActive: Boolean,
    val revision: Long
)

class SettingsStateHolder(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = OmniTrackPreferences(appContext)
    private fun snapshot(revision: Long): SettingsState {
        val storedSortOption = preferences.getString(OmniTrackPreferenceKeys.SortOption)
        val sortOption = LibrarySortOption.fromKey(storedSortOption)
        val sortDescending = if (storedSortOption != null && storedSortOption != sortOption.key) {
            LibrarySortOption.LAST_UPDATED.defaultDescending
        } else {
            when (preferences.getString(OmniTrackPreferenceKeys.SortDirection)) {
                null -> sortOption.defaultDescending
                "descending" -> true
                "ascending" -> false
                else -> LibrarySortOption.LAST_UPDATED.defaultDescending
            }
        }
        val publishingStatus = preferences.getString(OmniTrackPreferenceKeys.PublishingStatusFilter)
        return SettingsState(
            progressFolder = preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolder),
            progressFolderName = preferences.getString(OmniTrackPreferenceKeys.MoonReaderFolderName),
            booksFolder = preferences.getString(OmniTrackPreferenceKeys.MoonReaderBooksFolder),
            booksFolderName = preferences.getString(OmniTrackPreferenceKeys.MoonReaderBooksFolderName),
            sortOption = sortOption,
            sortDescending = sortDescending,
            selectedPublishingStatus = publishingStatus?.takeIf { it in PublishingStatus.values },
            shareStyle = preferences.getString(OmniTrackPreferenceKeys.ShareFormatStyle),
            selectedMediaType = preferences.getString(OmniTrackPreferenceKeys.SelectedMediaType),
            selectedUserStatus = preferences.getString(OmniTrackPreferenceKeys.SelectedUserStatus),
            selectedStatsPeriod = HistoryTimeframe.entries.find {
                it.name == preferences.getString(OmniTrackPreferenceKeys.SelectedStatsPeriod)
            } ?: HistoryTimeframe.DAYS_30,
            releaseChannel = preferences.releaseChannel(),
            liveLoggingActive = preferences.isLiveLoggingActive(),
            revision = revision
        )
    }
    private val _state = MutableStateFlow(snapshot(0))
    val state = _state.asStateFlow()
    private val listener = preferences.addListener { _state.value = snapshot(_state.value.revision + 1) }

    fun close() = preferences.removeListener(listener)

    fun recentSyncDiagnostics(): String = TrackerSyncDiagnosticLog.recent(appContext)

    val lokiActive = LokiLogPush.active
    val lokiStatus = LokiLogPush.status
    val lokiTokenStatus = preferences.lokiTokenOverride
        .map { LokiTokenStatus(!(it ?: BuildConfig.GRAFANA_LOKI_TOKEN).isNullOrBlank(), it != null) }
        .catch { emit(LokiTokenStatus(false, false, "Could not read the Loki token")) }

    fun setReleaseChannel(channel: ReleaseChannel) {
        if (channel != ReleaseChannel.BETA) LokiLogPush.stop()
        preferences.setReleaseChannel(channel)
    }

    fun setLiveLoggingActive(active: Boolean) {
        if (!active) LokiLogPush.stop()
        preferences.setLiveLoggingActive(active)
    }

    suspend fun startLokiLogging() =
        LokiLogPush.start(preferences, preferences.lokiUrl(), preferences.lokiUser(), preferences.lokiToken())

    suspend fun startLokiLogging(url: String, user: String) =
        LokiLogPush.start(preferences, url, user, preferences.lokiToken())

    suspend fun lokiUrl(): String = preferences.lokiUrl()

    suspend fun lokiUser(): String = preferences.lokiUser()

    suspend fun setLokiTokenOverride(token: String?) = preferences.setLokiTokenOverride(token)

    suspend fun sendLokiTestLog(): String = LokiLogPush.sendTest()

    suspend fun appLogs(fatal: Boolean): String = withContext(Dispatchers.IO) {
        check(preferences.releaseChannel() == ReleaseChannel.BETA) { "App logs require the beta channel" }
        if (fatal) AppLogger.fatalSnapshot() ?: "No saved fatal log." else AppLogger.snapshot().ifEmpty { "No recent app logs." }
    }

    fun stopLokiLogging() = LokiLogPush.stop()

    fun linkedFileUri(entryId: String): String? =
        preferences.getString(OmniTrackPreferenceKeys.moonReaderFileUri(entryId))

    fun linkedFileName(entryId: String): String? =
        preferences.getString(OmniTrackPreferenceKeys.moonReaderFileName(entryId))

    fun chapterOffset(entryId: String): Double? {
        val key = OmniTrackPreferenceKeys.moonReaderChapterOffset(entryId)
        return if (preferences.contains(key)) Double.fromBits(preferences.getLong(key)) else null
    }

    fun setLibrarySort(option: LibrarySortOption, descending: Boolean) {
        preferences.edit()
            .putString(OmniTrackPreferenceKeys.SortOption, option.key)
            .putString(OmniTrackPreferenceKeys.SortDirection, if (descending) "descending" else "ascending")
            .apply()
    }

    fun setPublishingStatusFilter(status: String?) {
        require(status == null || status in PublishingStatus.values) { "Invalid publishing status: $status" }
        val editor = preferences.edit()
        if (status == null) {
            editor.remove(OmniTrackPreferenceKeys.PublishingStatusFilter)
        } else {
            editor.putString(OmniTrackPreferenceKeys.PublishingStatusFilter, status)
        }
        editor.apply()
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

    fun setSelectedStatsPeriod(value: HistoryTimeframe) {
        preferences.edit().putString(OmniTrackPreferenceKeys.SelectedStatsPeriod, value.name).apply()
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
