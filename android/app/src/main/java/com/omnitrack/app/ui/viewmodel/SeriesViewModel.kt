package com.omnitrack.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.omnitrack.app.data.MangaBakaTitle
import com.omnitrack.app.data.OmniTrackPreferences
import com.omnitrack.app.data.OmniTrackPreferenceKeys
import com.omnitrack.app.data.PreferenceChange
import com.omnitrack.app.data.model.*
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
import com.omnitrack.app.worker.MangaBakaSyncWorker
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val mangaBaka = MangaBakaViewModel(appContext, repository)
    private val _mangaBakaImportMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val mangaBakaImportMessages = _mangaBakaImportMessages.asSharedFlow()
    private val moonReaderPrefs = OmniTrackPreferences(appContext)
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
    private var mangaBakaBatchJob: Job? = null
    private val moonReaderPrefsListener = moonReaderPrefs.addListener { change ->
        if (change == PreferenceChange.ALL ||
            change == PreferenceChange.MOON_READER_FOLDER ||
            change == PreferenceChange.MOON_READER_FILE_URI) {
            updateMoonReaderSchedule()
        }
    }

    init {
        viewModelScope.launch { mangaBaka.initialize() }
        viewModelScope.launch {
            repository.getMediaEntriesStream().collect { updateMoonReaderSchedule() }
        }
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
        moonReaderPrefs.removeListener(moonReaderPrefsListener)
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
        if (mangaBakaBatchJob?.isActive == true) return
        mangaBakaBatchJob = viewModelScope.launch { mangaBaka.batchImport(titles) }
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
                _mangaBakaImportMessages.emit("Entry linked to MangaBaka.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MangaBaka", "Could not link imported entry", e)
                _mangaBakaImportMessages.emit("Entry saved. MangaBaka: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun updateMoonReaderSchedule() {
        moonReaderScheduleJob?.cancel()
        moonReaderScheduleJob = viewModelScope.launch {
            val entries = repository.getMediaEntriesStream().first()
            val folder = moonReaderPrefs.getString(OmniTrackPreferenceKeys.MoonReaderFolder)
            val linked = entries.any {
                moonReaderPrefs.getString(OmniTrackPreferenceKeys.moonReaderFileUri(it.id)) != null
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
        if (moonReaderPrefs.getString(key) == folder) {
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
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()
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
    suspend fun createSeries(request: CreateSeriesRequest) = repository.createSeries(request)
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
    suspend fun createPlatform(request: CreatePlatformRequest) = repository.createPlatform(request)
    suspend fun updatePlatform(id: String, request: UpdatePlatformRequest) =
        repository.updatePlatform(id, request)
    suspend fun deletePlatform(id: String) = repository.deletePlatform(id)
    suspend fun createMediaEntry(
        request: CreateEntryRequest,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ) = repository.createMediaEntry(request, creators, alternativeTitles)
    suspend fun updateMediaEntry(
        id: String,
        request: UpdateEntryRequest,
        creators: List<Pair<String, String>>,
        alternativeTitles: List<String>
    ) = repository.updateMediaEntry(id, request, creators, alternativeTitles)
    suspend fun updateEntryStatus(id: String, status: String) = repository.updateEntryStatus(id, status)

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