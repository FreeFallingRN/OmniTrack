package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.feature.MediaLookupService
import com.omnitrack.app.worker.MangaBakaSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MangaBakaOwner(
    context: Context,
    repository: SeriesRepository,
    private val isRefreshing: StateFlow<Boolean>,
    mediaLookup: MediaLookupService
) : ViewModel() {
    private val appContext = context.applicationContext
    val mangaBaka = MangaBakaViewModel(appContext, repository, mediaLookup)
    private val _importMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val importMessages = _importMessages.asSharedFlow()
    private var batchJob: Job? = null

    init {
        viewModelScope.launch { mangaBaka.initialize() }
        viewModelScope.launch {
            combine(mangaBaka.state, repository.getMediaEntriesStream()) { state, entries ->
                if (!state.initialized) null else
                    state.connected && state.links.values.any { link -> entries.any { it.id == link.entryId } }
            }.distinctUntilChanged().collect { enabled ->
                when (enabled) {
                    true -> MangaBakaSyncWorker.schedule(appContext)
                    false -> MangaBakaSyncWorker.cancel(appContext)
                    null -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        mangaBaka.close()
        super.onCleared()
    }

    fun handleMangaBakaCallback(uri: Uri) {
        viewModelScope.launch { mangaBaka.completeAuthorization(uri) }
    }

    fun fetchMangaBakaLibrary() {
        viewModelScope.launch { mangaBaka.fetch() }
    }

    fun syncMangaBakaProgress(manual: Boolean = false) {
        viewModelScope.launch {
            isRefreshing.first { !it }
            mangaBaka.sync(manual)
        }
    }

    fun syncAndMatchMangaBakaLibrary() {
        viewModelScope.launch {
            isRefreshing.first { !it }
            mangaBaka.syncAndMatchLibrary()
        }
    }

    fun addMangaBakaEntry(title: MangaBakaTitle, otherType: String? = null) {
        viewModelScope.launch { mangaBaka.add(title, otherType) }
    }

    fun batchImportMangaBakaEntries(titles: List<MangaBakaTitle>) {
        if (batchJob?.isActive == true) return
        batchJob = viewModelScope.launch { mangaBaka.batchImport(titles) }
    }

    fun linkMangaBakaEntry(title: MangaBakaTitle, entryId: String, offset: Double) {
        viewModelScope.launch { mangaBaka.link(title, entryId, offset) }
    }

    fun linkAllMatchedMangaBakaEntries(matches: List<Pair<MangaBakaTitle, String>>) {
        viewModelScope.launch { mangaBaka.linkAllMatched(matches) }
    }

    fun unlinkMangaBakaEntry(title: MangaBakaTitle) {
        viewModelScope.launch { mangaBaka.unlink(title) }
    }

    fun linkImportedMangaBakaEntry(seriesId: Long, entryId: String, unitName: String, sourceUrl: String) {
        viewModelScope.launch {
            try {
                mangaBaka.linkImportedSeries(seriesId, entryId, unitName, sourceUrl)
                _importMessages.emit("Entry linked to MangaBaka.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MangaBaka", "Could not link imported entry", e)
                _importMessages.emit("Entry saved. MangaBaka: ${e.message ?: "Unknown error"}")
            }
        }
    }
}
