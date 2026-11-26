package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.PreferenceChange
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.utils.MOON_READER_FOLDER_EXPIRED
import com.omnitrack.app.utils.MoonReaderFolderAccessException
import com.omnitrack.app.utils.MoonReaderMatch
import com.omnitrack.app.utils.MoonReaderImport
import com.omnitrack.app.utils.MoonReaderScan
import com.omnitrack.app.utils.MoonReaderSync
import com.omnitrack.app.utils.hasMoonReaderFolderAccess
import com.omnitrack.app.utils.moonReaderProviderCall
import com.omnitrack.app.worker.MoonReaderSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MoonReaderViewModel(
    context: Context,
    private val repository: SeriesRepository,
    private val isRefreshing: StateFlow<Boolean>
) : ViewModel() {
    private val appContext = context.applicationContext
    private val prefs = OmniTrackPreferences(appContext)
    private val sync = MoonReaderSync(appContext, repository)
    private val _moonReaderBusy = MutableStateFlow(false)
    val moonReaderBusy = _moonReaderBusy.asStateFlow()
    private val _needsProgressFolderReauth = MutableStateFlow(false)
    val needsProgressFolderReauth = _needsProgressFolderReauth.asStateFlow()
    private val _needsBooksFolderReauth = MutableStateFlow(false)
    val needsBooksFolderReauth = _needsBooksFolderReauth.asStateFlow()
    private val _moonReaderMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val moonReaderMessages = _moonReaderMessages.asSharedFlow()
    private var lastCheck: Long? = null
    private var scheduleJob: Job? = null
    private val listener = prefs.addListener { change ->
        if (change == PreferenceChange.ALL ||
            change == PreferenceChange.MOON_READER_FOLDER ||
            change == PreferenceChange.MOON_READER_FILE_URI) {
            updateMoonReaderSchedule()
        }
    }

    init {
        viewModelScope.launch {
            repository.getMediaEntriesStream().collect { updateMoonReaderSchedule() }
        }
    }

    override fun onCleared() {
        prefs.removeListener(listener)
        super.onCleared()
    }

    fun updateMoonReaderSchedule() {
        scheduleJob?.cancel()
        scheduleJob = viewModelScope.launch {
            val entries = repository.getMediaEntriesStream().first()
            val folder = prefs.getString(OmniTrackPreferenceKeys.MoonReaderFolder)
            val linked = entries.any {
                prefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.id)) != null
            }
            if (linked && folder != null && checkMoonReaderFolder(folder, books = false)) {
                MoonReaderSyncWorker.schedule(appContext)
            } else {
                MoonReaderSyncWorker.cancel(appContext)
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
        sync.apply(match, newProgress, folder)
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
        return result.copy(matches = matches) to values
    }

    fun syncMoonReader(manual: Boolean = false) {
        if (_moonReaderBusy.value) return
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastCheck?.let { now - it < 60_000L } == true) return
        lastCheck = now
        _moonReaderBusy.value = true
        viewModelScope.launch {
            try {
                isRefreshing.first { !it }
                sync.sync(manual) { _moonReaderMessages.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MoonReaderFolderAccessException) {
                setMoonReaderFolderAccess(e.folder, books = false, valid = false)
            } catch (e: Exception) {
                Log.e("MoonReaderSync", "Moon+ Reader sync failed", e)
                _moonReaderMessages.emit("Moon+ Reader sync failed: ${e.message ?: "Unknown error"}")
            } finally {
                _moonReaderBusy.value = false
            }
        }
    }
}
