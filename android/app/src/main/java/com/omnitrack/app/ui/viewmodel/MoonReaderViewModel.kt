package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.PreferenceChange
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.utils.MOON_READER_FOLDER_EXPIRED
import com.omnitrack.app.utils.MoonReaderMatch
import com.omnitrack.app.utils.MoonReaderAmbiguous
import com.omnitrack.app.utils.MoonReaderProgress
import com.omnitrack.app.utils.MoonReaderImport
import com.omnitrack.app.utils.MoonReaderScan
import com.omnitrack.app.utils.MoonReaderSync
import com.omnitrack.app.utils.hasMoonReaderFolderAccess
import com.omnitrack.app.utils.moonReaderProviderCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MoonReaderViewModel(
    context: Context,
    private val repository: SeriesRepository
) : ViewModel() {
    private val appContext = context.applicationContext
    private val prefs = OmniTrackPreferences(appContext)
    private val sync = MoonReaderSync(appContext, repository)
    private val _needsProgressFolderReauth = MutableStateFlow(false)
    val needsProgressFolderReauth = _needsProgressFolderReauth.asStateFlow()
    private val _needsBooksFolderReauth = MutableStateFlow(false)
    val needsBooksFolderReauth = _needsBooksFolderReauth.asStateFlow()
    private val _moonReaderMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val moonReaderMessages = _moonReaderMessages.asSharedFlow()
    private var folderAccessJob: Job? = null
    private val listener = prefs.addListener { change ->
        if (change == PreferenceChange.ALL ||
            change == PreferenceChange.MOON_READER_FOLDER ||
            change == PreferenceChange.MOON_READER_FILE_URI) {
            refreshMoonReaderFolderAccess()
        }
    }

    init {
        viewModelScope.launch {
            repository.getMediaEntriesStream().collect { refreshMoonReaderFolderAccess() }
        }
    }

    override fun onCleared() {
        prefs.removeListener(listener)
        super.onCleared()
    }

    fun refreshMoonReaderFolderAccess() {
        folderAccessJob?.cancel()
        folderAccessJob = viewModelScope.launch {
            val entries = repository.getMediaEntriesStream().first()
            val folder = prefs.getString(OmniTrackPreferenceKeys.MoonReaderFolder)
            val linked = entries.any {
                prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.id)) != null
            }
            if (linked && folder != null) {
                checkMoonReaderFolder(folder, books = false)
            }
        }
    }

    suspend fun checkMoonReaderFolder(folder: String, books: Boolean): Boolean {
        val valid = hasMoonReaderFolderAccess(appContext, Uri.parse(folder))
        setMoonReaderFolderAccess(folder, books, valid)
        return valid
    }

    suspend fun setMoonReaderFolderAccess(folder: String, books: Boolean, valid: Boolean) {
        val key = if (books) OmniTrackPreferenceKeys.MoonReaderBooksFolder else OmniTrackPreferenceKeys.MoonReaderFolder
        if (prefs.getString(key) == folder) {
            val state = if (books) _needsBooksFolderReauth else _needsProgressFolderReauth
            val wasExpired = state.value
            state.value = !valid
            if (!valid && !wasExpired) _moonReaderMessages.emit(MOON_READER_FOLDER_EXPIRED)
        }
    }

    suspend fun importMoonReaderProgress(match: MoonReaderMatch, newProgress: Double, folder: String?) {
        check(prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(match.entry.id)) == null) {
            "${match.entry.title} is already linked to a Moon+ Reader file"
        }
        sync.apply(match, newProgress, folder)
    }

    suspend fun importNewMoonReaderProgress(entryId: String, progress: MoonReaderProgress, value: Double, folder: String?) {
        val entry = repository.getMediaEntriesStream().first().firstOrNull { it.id == entryId }
        checkNotNull(entry) { "Saved entry is not available; refresh the library and link it manually" }
        check(entry.type == MediaType.NOVEL && isChapterUnit(entry.unitName)) {
            "Saved entry no longer uses novel chapters; check it before linking"
        }
        importMoonReaderProgress(MoonReaderMatch(entry, progress), value, folder)
    }

    suspend fun unlinkMoonReaderBook(entryId: String) {
        sync.unlink(entryId)
    }

    fun isChapterUnit(unitName: String): Boolean = MoonReaderSync.isChapterUnit(unitName)

    suspend fun scan(folder: Uri, entries: List<MediaEntry>): Pair<MoonReaderScan, Map<String, String>>? {
        if (!checkMoonReaderFolder(folder.toString(), books = false)) return null
        val result = moonReaderProviderCall(60_000L) {
            MoonReaderImport.scan(appContext, folder, true, entries)
        } ?: run {
            setMoonReaderFolderAccess(folder.toString(), books = false, valid = false)
            return null
        }
        val linkedUris = entries.mapNotNull {
            prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.id))
        }.toSet()
        val matches = result.matches.filter {
            isChapterUnit(it.entry.unitName) &&
                it.progress.fileUri != null && it.progress.fileName != null &&
                prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.entry.id)) == null &&
                it.progress.fileUri.toString() !in linkedUris
        }
        val values = matches.associate { match ->
            val key = OmniTrackPreferenceKeys.moonReaderChapterOffset(match.entry.id)
            val chapter = match.progress.spineChapterIndex.toDouble() + 1.0
            val calibrated = if (prefs.contains(key)) {
                val offset = Double.fromBits(prefs.getLong(key))
                check(offset.isFinite()) { "Invalid saved chapter offset for ${match.entry.title}" }
                java.math.BigDecimal.valueOf(offset)
                    .add(java.math.BigDecimal.valueOf(chapter)).toPlainString()
            } else {
                match.entry.currentUnit.toString()
            }
            match.entry.id to calibrated
        }
        val deferred = result.matches.filterNot { it in matches }
            .map { MoonReaderAmbiguous(it.progress, listOf(it.entry)) }
        return result.copy(matches = matches, ambiguous = result.ambiguous + deferred) to values
    }

}
