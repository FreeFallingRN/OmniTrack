package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnitrack.app.domain.model.HistoryTimeframe
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.OverallProgressStats
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.domain.repository.SeriesRepository
import com.omnitrack.app.utils.get7DDates
import com.omnitrack.app.utils.MoonReaderMatch
import com.omnitrack.app.utils.MoonReaderSync
import com.omnitrack.app.utils.MoonReaderFolderAccessException
import com.omnitrack.app.utils.MOON_READER_FOLDER_EXPIRED
import com.omnitrack.app.utils.hasMoonReaderFolderAccess
import com.omnitrack.app.worker.MoonReaderSyncWorker
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
    private val moonReaderPrefs = appContext.getSharedPreferences("omnitrack_prefs", Context.MODE_PRIVATE)
    private val moonReaderSync = MoonReaderSync(appContext, repository)
    private val _moonReaderBusy = MutableStateFlow(false)
    val moonReaderBusy: StateFlow<Boolean> = _moonReaderBusy.asStateFlow()
    private val _needsProgressFolderReauth = MutableStateFlow(false)
    val needsProgressFolderReauth: StateFlow<Boolean> = _needsProgressFolderReauth.asStateFlow()
    private val _needsBooksFolderReauth = MutableStateFlow(false)
    val needsBooksFolderReauth: StateFlow<Boolean> = _needsBooksFolderReauth.asStateFlow()
    private val _moonReaderMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val moonReaderMessages = _moonReaderMessages.asSharedFlow()
    private var lastMoonReaderCheck: Long? = null
    private var moonReaderScheduleJob: Job? = null
    private val moonReaderPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "moon_reader_folder" || key.startsWith("moon_reader_file_uri_")) {
            updateMoonReaderSchedule()
        }
    }

    init {
        moonReaderPrefs.registerOnSharedPreferenceChangeListener(moonReaderPrefsListener)
        viewModelScope.launch {
            repository.getMediaEntriesStream().collect { updateMoonReaderSchedule() }
        }
    }

    override fun onCleared() {
        moonReaderPrefs.unregisterOnSharedPreferenceChangeListener(moonReaderPrefsListener)
        super.onCleared()
    }

    fun updateMoonReaderSchedule() {
        moonReaderScheduleJob?.cancel()
        moonReaderScheduleJob = viewModelScope.launch {
            val entries = repository.getMediaEntriesStream().first()
            val folder = moonReaderPrefs.getString("moon_reader_folder", null)
            val linked = entries.any { moonReaderPrefs.getString("moon_reader_file_uri_${it.id}", null) != null }
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
        val key = if (books) "moon_reader_books_folder" else "moon_reader_folder"
        if (moonReaderPrefs.getString(key, null) == folder) {
            val state = if (books) _needsBooksFolderReauth else _needsProgressFolderReauth
            val wasExpired = state.value
            state.value = !valid
            if (!valid && !wasExpired) _moonReaderMessages.emit(MOON_READER_FOLDER_EXPIRED)
        }
    }

    private val selectedPeriodTabFlow = MutableStateFlow(HistoryTimeframe.DAYS_30)
    private val periodOffsetFlow = MutableStateFlow(0)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private var activeRefreshes = 0

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

    fun selectPeriodTab(tab: HistoryTimeframe) {
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
            repository.updateProgress(id, newProgress, source)
        }
    }

    suspend fun importMoonReaderProgress(match: MoonReaderMatch, newProgress: Double, folder: String?) {
        moonReaderSync.apply(match, newProgress, folder)
    }

    suspend fun unlinkMoonReaderBook(entryId: String) {
        moonReaderSync.unlink(entryId)
    }

    fun syncMoonReader(manual: Boolean = false) {
        if (_moonReaderBusy.value) return
        val now = SystemClock.elapsedRealtime()
        val lastCheck = lastMoonReaderCheck
        if (!manual && lastCheck != null && now - lastCheck < 60_000L) return
        lastMoonReaderCheck = now
        _moonReaderBusy.value = true
        viewModelScope.launch {
            try {
                isRefreshing.first { !it }
                moonReaderSync.sync(manual) { _moonReaderMessages.emit(it) }
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

    private data class PeriodNavState(
        val tab: HistoryTimeframe,
        val offset: Int,
        val canGoPrevious: Boolean,
        val canGoNext: Boolean
    )

    fun refreshData() {
        viewModelScope.launch {
            activeRefreshes++
            _isRefreshing.value = true
            try {
                repository.fetchFromSupabase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                activeRefreshes--
                _isRefreshing.value = activeRefreshes > 0
            }
        }
    }
}