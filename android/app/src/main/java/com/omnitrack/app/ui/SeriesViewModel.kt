package com.omnitrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.domain.model.DailyProgressStats
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
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

    val recentActivity: StateFlow<List<ProgressHistoryItem>> = repository.getRecentActivityFeed()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val progressStats: StateFlow<OverallProgressStats> = repository.getProgressStats()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OverallProgressStats(0.0, 0, 0, emptyList())
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

    fun updateProgress(
        id: String,
        newProgress: Double,
        source: ProgressSource = ProgressSource.DETAIL_SHEET
    ) {
        viewModelScope.launch {
            repository.updateProgress(id, newProgress, source)
        }
    }

    fun incrementUnitCount(
        id: String,
        delta: Double = 1.0,
        source: ProgressSource = ProgressSource.INCREMENT
    ) {
        viewModelScope.launch {
            try {
                repository.incrementProgress(id, delta, source)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteMediaEntry(id: String) {
        viewModelScope.launch {
            repository.deleteMediaEntry(id)
        }
    }

    suspend fun getDailyStats(days: Int = 30): List<DailyProgressStats> {
        return repository.getDailyStats(days)
    }
}