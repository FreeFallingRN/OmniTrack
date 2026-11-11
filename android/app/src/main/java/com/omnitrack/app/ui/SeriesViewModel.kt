package com.omnitrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesViewModel(
    private val repository: SeriesRepository
) : ViewModel() {

    // Expose a reactive state flow of all series items from the local Room cache
    val seriesList: StateFlow<List<Series>> = repository.getSeriesStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Trigger background sync on ViewModel creation if needed
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            try {
                repository.fetchFromSupabase()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateProgress(id: String, newProgress: Double) {
        viewModelScope.launch {
            repository.updateProgress(id, newProgress)
        }
    }

    fun toggleActiveTarget(id: String, isActive: Boolean) {
        viewModelScope.launch {
            repository.setActiveTarget(id, isActive)
        }
    }

    fun deleteSeries(id: String) {
        viewModelScope.launch {
            repository.deleteSeries(id)
        }
    }
}