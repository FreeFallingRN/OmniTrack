package com.omnitrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesViewModel(
    private val repository: SeriesRepository
) : ViewModel() {

    val mediaEntries: StateFlow<List<MediaEntry>> = repository.getMediaEntriesStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            refreshData()
        }
    }

    suspend fun refreshData() {
        try {
            repository.fetchFromSupabase()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateProgress(id: String, newProgress: Double) {
        viewModelScope.launch {
            repository.updateProgress(id, newProgress)
        }
    }

    fun deleteMediaEntry(id: String) {
        viewModelScope.launch {
            repository.deleteMediaEntry(id)
        }
    }
}