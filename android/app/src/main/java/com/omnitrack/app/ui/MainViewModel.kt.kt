package com.omnitrack.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.local.SeriesEntity
import com.omnitrack.app.data.repository.SeriesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SeriesRepository(AppDatabase.getInstance(application))

    val seriesList: StateFlow<List<SeriesEntity>> = repository.allSeries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                repository.refreshFromRemote()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateProgress(series: SeriesEntity, delta: Double) {
        viewModelScope.launch {
            repository.updateProgress(getApplication(), series, delta)
        }
    }

    fun setActiveTarget(seriesId: String) {
        viewModelScope.launch {
            repository.setActiveTarget(seriesId)
        }
    }
}