package com.omnitrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.domain.model.DailyProgressStats
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.utils.get30DWeeks
import com.omnitrack.app.utils.get7DDates
import com.omnitrack.app.utils.getAllTimeYears
import com.omnitrack.app.utils.getYearMonths
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SeriesViewModelState(
    val mediaEntries: List<MediaEntry>,
    val recentActivity: List<ProgressHistoryItem>,
    val progressStats: OverallProgressStats,
    val selectedPeriodTab: String = "30D",
    val periodOffset: Int = 0,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
)

class SeriesViewModel(
    private val repository: SeriesRepository
) : ViewModel() {

    private val selectedPeriodTabFlow = MutableStateFlow("30D")
    private val periodOffsetFlow = MutableStateFlow(0)

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
            "All Time" -> false
            else -> minOffset != null && clampedOffset > minOffset
        }
        val canGoNext = when (tab) {
            "All Time" -> false
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

    fun selectPeriodTab(tab: String) {
        selectedPeriodTabFlow.value = tab
        periodOffsetFlow.value = 0
    }

    fun stepPeriod(delta: Int) {
        val tab = selectedPeriodTabFlow.value
        if (tab == "All Time") return
        val earliestDate: LocalDate? = recentActivity.value
            .mapNotNull { it.toEntryLocalDate() }
            .minOrNull()
        val today = LocalDate.now()
        val minOffset = computeMinOffset(tab, earliestDate, today) ?: return
        val nextOffset = (periodOffsetFlow.value + delta).coerceIn(minOffset, 0)
        periodOffsetFlow.value = nextOffset
    }

    private fun computeMinOffset(tab: String, earliestDate: LocalDate?, today: LocalDate): Int? {
        if (earliestDate == null) return 0
        return when (tab) {
            "7D" -> {
                val earliestWeekStart = get7DDates(0, earliestDate).first()
                val currentWeekStart = get7DDates(0, today).first()
                -ChronoUnit.WEEKS.between(earliestWeekStart, currentWeekStart).toInt().coerceAtLeast(0)
            }
            "30D" -> {
                val earliestYm = YearMonth.from(earliestDate)
                val currentYm = YearMonth.from(today)
                -ChronoUnit.MONTHS.between(earliestYm, currentYm).toInt().coerceAtLeast(0)
            }
            "Year" -> {
                -(today.year - earliestDate.year).coerceAtLeast(0)
            }
            else -> 0
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

    private data class PeriodNavState(
        val tab: String,
        val offset: Int,
        val canGoPrevious: Boolean,
        val canGoNext: Boolean
    )

    fun refreshData() {
        viewModelScope.launch {
            try {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}