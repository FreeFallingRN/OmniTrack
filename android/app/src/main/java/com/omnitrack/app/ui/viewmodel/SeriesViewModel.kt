package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.omnitrack.app.data.TrackerStatusSync
import com.omnitrack.app.worker.TrackerStatusSyncWorker
import com.omnitrack.app.domain.model.CreateEntryCommand
import com.omnitrack.app.domain.model.CreatePlatformCommand
import com.omnitrack.app.domain.model.CreateSeriesCommand
import com.omnitrack.app.domain.model.UpdateEntryCommand
import com.omnitrack.app.domain.model.UpdatePlatformCommand
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.domain.model.HistoryTimeframe
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.utils.get7DDates
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SeriesViewModelState(
    val mediaEntries: List<MediaEntry>,
    val recentActivity: List<ProgressHistoryItem>,
    val progressStats: OverallProgressStats,
    val selectedPeriodTab: HistoryTimeframe = HistoryTimeframe.DAYS_30,
    val periodOffset: Int = 0,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
)

class SeriesViewModel(
    private val repository: SeriesRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val selectedPeriodTabFlow = MutableStateFlow(HistoryTimeframe.DAYS_30)
    private val periodOffsetFlow = MutableStateFlow(0)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()
    private var activeRefreshes = 0

    init {
        TrackerStatusSyncWorker.schedule(appContext)
    }

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

    private val periodNavigationFlow = combine(
        selectedPeriodTabFlow,
        periodOffsetFlow,
        recentActivity
    ) { tab, offset, activity ->
        val earliestDate: LocalDate? = activity
            .mapNotNull { it.toEntryLocalDate() }
            .minOrNull()
        val today = LocalDate.now()
        val minOffset = computeMinOffset(tab, earliestDate, today)
        val clampedOffset = offset.coerceIn(minOffset ?: offset, 0)

        val canGoPrevious = when (tab) {
            HistoryTimeframe.ALL_TIME -> false
            else -> minOffset != null && clampedOffset > minOffset
        }
        val canGoNext = when (tab) {
            HistoryTimeframe.ALL_TIME -> false
            else -> clampedOffset < 0
        }
        PeriodNavState(tab, clampedOffset, canGoPrevious, canGoNext)
    }

    val state: StateFlow<SeriesViewModelState> = combine(
        mediaEntries,
        recentActivity,
        progressStats,
        periodNavigationFlow
    ) { entries, activity, stats, nav ->
        SeriesViewModelState(
            mediaEntries = entries,
            recentActivity = activity,
            progressStats = stats,
            selectedPeriodTab = nav.tab,
            periodOffset = nav.offset,
            canGoPrevious = nav.canGoPrevious,
            canGoNext = nav.canGoNext
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SeriesViewModelState(
            mediaEntries = emptyList(),
            recentActivity = emptyList(),
            progressStats = OverallProgressStats(0.0, 0, 0, emptyList())
        )
    )

    fun selectPeriodTab(tab: HistoryTimeframe, resetIfSelected: Boolean = true) {
        if (!resetIfSelected && selectedPeriodTabFlow.value == tab) return
        selectedPeriodTabFlow.value = tab
        periodOffsetFlow.value = 0
    }

    fun stepPeriod(delta: Int) {
        val tab = selectedPeriodTabFlow.value
        if (tab == HistoryTimeframe.ALL_TIME) return
        val earliestDate: LocalDate? = recentActivity.value
            .mapNotNull { it.toEntryLocalDate() }
            .minOrNull()
        val today = LocalDate.now()
        val minOffset = computeMinOffset(tab, earliestDate, today) ?: return
        val nextOffset = (periodOffsetFlow.value + delta).coerceIn(minOffset, 0)
        periodOffsetFlow.value = nextOffset
    }

    private fun computeMinOffset(tab: HistoryTimeframe, earliestDate: LocalDate?, today: LocalDate): Int? {
        if (earliestDate == null) return 0
        return when (tab) {
            HistoryTimeframe.DAYS_7 -> {
                val earliestWeekStart = get7DDates(0, earliestDate).first()
                val currentWeekStart = get7DDates(0, today).first()
                -ChronoUnit.WEEKS.between(earliestWeekStart, currentWeekStart).toInt().coerceAtLeast(0)
            }
            HistoryTimeframe.DAYS_30 -> {
                val earliestYm = YearMonth.from(earliestDate)
                val currentYm = YearMonth.from(today)
                -ChronoUnit.MONTHS.between(earliestYm, currentYm).toInt().coerceAtLeast(0)
            }
            HistoryTimeframe.YEAR -> {
                -(today.year - earliestDate.year).coerceAtLeast(0)
            }
            HistoryTimeframe.ALL_TIME -> 0
        }
    }

    fun updateProgress(
        id: String,
        newProgress: Double,
        source: ProgressSource = ProgressSource.DETAIL_SHEET
    ) {
        viewModelScope.launch {
            try {
                repository.updateProgress(id, newProgress, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SeriesViewModel", "Could not update progress", e)
                Toast.makeText(appContext, e.message ?: "Could not update progress", Toast.LENGTH_LONG).show()
            }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SeriesViewModel", "Could not update progress", e)
                Toast.makeText(appContext, e.message ?: "Could not update progress", Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun deleteMediaEntry(id: String): Result<Unit> =
        try {
            repository.deleteMediaEntry(id)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SeriesViewModel", "Could not delete entry", e)
            Result.failure(e)
        }

    suspend fun getSeries() = repository.getSeries()
    suspend fun getAuthors() = repository.getAuthors()
    suspend fun getPlatforms() = repository.getPlatforms()
    suspend fun createSeries(request: CreateSeriesCommand) = repository.createSeries(request)
    suspend fun updateSeries(id: String, title: String, rating: Int?) =
        repository.updateSeries(id, title, rating)
    suspend fun deleteSeries(id: String) = repository.deleteSeries(id)
    suspend fun createAuthor(name: String, nativeName: String? = null, imageUrl: String? = null, bio: String? = null) =
        repository.createAuthor(name, nativeName, imageUrl, bio)
    suspend fun updateAuthor(
        id: String,
        name: String,
        nativeName: String? = null,
        imageUrl: String? = null,
        bio: String? = null
    ) = repository.updateAuthor(id, name, nativeName, imageUrl, bio)
    suspend fun deleteAuthor(id: String) = repository.deleteAuthor(id)
    suspend fun createPlatform(request: CreatePlatformCommand) = repository.createPlatform(request)
    suspend fun updatePlatform(id: String, request: UpdatePlatformCommand) =
        repository.updatePlatform(id, request)
    suspend fun deletePlatform(id: String) = repository.deletePlatform(id)
    suspend fun createMediaEntry(
        request: CreateEntryCommand,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ) = repository.createMediaEntry(request, creators, alternativeTitles)
    suspend fun updateMediaEntry(
        id: String,
        request: UpdateEntryCommand,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ) {
        if (repository.updateMediaEntry(id, request, creators, alternativeTitles)) scheduleStatusSync()
    }
    suspend fun updateEntryStatus(id: String, status: String) {
        repository.updateEntryStatus(id, status)
        scheduleStatusSync()
    }

    private fun scheduleStatusSync() {
        TrackerStatusSyncWorker.schedule(appContext)
        viewModelScope.launch {
            val errors = TrackerStatusSync(appContext, repository).sync()
            if (errors.isNotEmpty()) {
                Toast.makeText(
                    appContext, "Status saved locally; sync will retry: ${errors.joinToString("; ")}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class PeriodNavState(
        val tab: HistoryTimeframe,
        val offset: Int,
        val canGoPrevious: Boolean,
        val canGoNext: Boolean
    )

    fun refreshData() {
        activeRefreshes++
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                repository.fetchFromSupabase()
                _refreshError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SeriesViewModel", "Refresh failed", e)
                val message = "Refresh failed. Please try again."
                _refreshError.value = message
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            } finally {
                activeRefreshes--
                _isRefreshing.value = activeRefreshes > 0
            }
        }
    }
}