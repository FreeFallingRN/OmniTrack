package com.omnitrack.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.omnitrack.app.BuildConfig
import com.omnitrack.app.domain.model.ReleaseChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AniListLink(
    val entryId: String,
    val initialSync: Boolean = true,
    val mediaListId: Int? = null,
    val lastProgress: Int? = null,
    val lastUpdatedAt: Long? = null
)

sealed class PreferenceKey<T> protected constructor(internal val name: String) {
    @ConsistentCopyVisibility
    data class StringKey internal constructor(private val key: String) : PreferenceKey<String>(key)

    @ConsistentCopyVisibility
    data class LongKey internal constructor(private val key: String) : PreferenceKey<Long>(key)

    @ConsistentCopyVisibility
    data class IntKey internal constructor(private val key: String) : PreferenceKey<Int>(key)
}

object OmniTrackPreferenceKeys {
    val ReleaseChannel = PreferenceKey.StringKey("release_channel")
    val LiveLogging = PreferenceKey.IntKey("live_logging")
    val SortOption = PreferenceKey.StringKey("sort_option")
    val SortDirection = PreferenceKey.StringKey("sort_direction")
    val PublishingStatusFilter = PreferenceKey.StringKey("publishing_status_filter")
    val ShareFormatStyle = PreferenceKey.StringKey("share_format_style")
    val SelectedMediaType = PreferenceKey.StringKey("selected_media_type")
    val SelectedUserStatus = PreferenceKey.StringKey("selected_user_status")
    val SelectedStatsPeriod = PreferenceKey.StringKey("selected_stats_period")
    val MoonReaderFolder = PreferenceKey.StringKey("moon_reader_folder")
    val MoonReaderFolderName = PreferenceKey.StringKey("moon_reader_folder_name")
    val MoonReaderBooksFolder = PreferenceKey.StringKey("moon_reader_books_folder")
    val MoonReaderBooksFolderName = PreferenceKey.StringKey("moon_reader_books_folder_name")
    val AniListAutoSync = PreferenceKey.IntKey("anilist_auto_sync")
    fun aniListLinks(userId: Int) = PreferenceKey.StringKey("anilist_links_$userId")

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

    fun moonReaderFileSize(entryId: String) =
        PreferenceKey.LongKey("moon_reader_file_size_$entryId")

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
    private val lokiPreferences = lokiDataStore(context.applicationContext)

    val lokiTokenOverride = lokiPreferences.data.map { it[LOKI_TOKEN] }

    suspend fun lokiToken(): String = lokiTokenOverride.first() ?: BuildConfig.GRAFANA_LOKI_TOKEN

    suspend fun setLokiTokenOverride(token: String?) {
        require(token == null || (token.length in 4..4096 && token.none { it.isISOControl() })) {
            "Enter a valid Loki token"
        }
        lokiPreferences.edit { prefs ->
            if (token == null) prefs.remove(LOKI_TOKEN) else prefs[LOKI_TOKEN] = token
        }
    }

    fun getString(key: PreferenceKey.StringKey, defaultValue: String? = null): String? =
        preferences.getString(key.name, defaultValue)

    fun getLong(key: PreferenceKey.LongKey, defaultValue: Long = 0L): Long =
        preferences.getLong(key.name, defaultValue)

    fun getInt(key: PreferenceKey.IntKey, defaultValue: Int = 0): Int =
        preferences.getInt(key.name, defaultValue)

    fun contains(key: PreferenceKey<*>): Boolean = preferences.contains(key.name)

    fun releaseChannel(): ReleaseChannel =
        ReleaseChannel.entries.find { it.name == getString(OmniTrackPreferenceKeys.ReleaseChannel) }
            ?: ReleaseChannel.STABLE

    fun isLiveLoggingActive(): Boolean =
        releaseChannel() == ReleaseChannel.BETA && getInt(OmniTrackPreferenceKeys.LiveLogging) == 1

    fun setReleaseChannel(channel: ReleaseChannel) = synchronized(channelLock) {
        val editor = edit().putString(OmniTrackPreferenceKeys.ReleaseChannel, channel.name)
        if (channel == ReleaseChannel.STABLE) editor.putInt(OmniTrackPreferenceKeys.LiveLogging, 0)
        editor.apply()
    }

    fun setLiveLoggingActive(active: Boolean) = synchronized(channelLock) {
        require(!active || releaseChannel() == ReleaseChannel.BETA) {
            "Live logging requires the beta channel"
        }
        edit().putInt(OmniTrackPreferenceKeys.LiveLogging, if (active) 1 else 0).apply()
    }

    fun aniListLinks(userId: Int): Map<Int, AniListLink> {
        val saved = getString(OmniTrackPreferenceKeys.aniListLinks(userId)) ?: return emptyMap()
        return Json.decodeFromString(saved)
    }

    fun saveAniListLinks(userId: Int, links: Map<Int, AniListLink>) {
        check(edit().putString(OmniTrackPreferenceKeys.aniListLinks(userId), Json.encodeToString(links)).commit()) {
            "Could not save AniList links"
        }
    }

    fun unlinkAniListEntry(entryId: String) {
        val editor = preferences.edit()
        var changed = false
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("anilist_links_") && value is String) {
                val links = Json.decodeFromString<Map<Int, AniListLink>>(value)
                val retained = links.filterValues { it.entryId != entryId }
                if (retained.size != links.size) {
                    editor.putString(key, Json.encodeToString(retained))
                    changed = true
                }
            }
        }
        check(!changed || editor.commit()) { "Could not remove AniList links" }
    }

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
            .remove(OmniTrackPreferenceKeys.moonReaderFileSize(entryId))
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
        private val channelLock = Any()
        private val LOKI_TOKEN = stringPreferencesKey("loki_token")
        @Volatile private var lokiStore: DataStore<Preferences>? = null

        private fun lokiDataStore(context: Context): DataStore<Preferences> =
            lokiStore ?: synchronized(this) {
                lokiStore ?: PreferenceDataStoreFactory.create(
                    produceFile = { File(context.noBackupFilesDir, "loki.preferences_pb") }
                ).also { lokiStore = it }
            }
    }
}
