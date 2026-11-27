package com.omnitrack.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.omnitrack.app.domain.model.*
import com.omnitrack.app.ui.LocalFeatureServices
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.get7DDates
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryStatsScreen(
    viewModel: SeriesViewModel,
    onSelectEntry: (MediaEntry) -> Unit
) {
    val uiState by viewModel.state.collectAsState()
    val settings = LocalFeatureServices.current.settings
    val settingsState by settings.state.collectAsState()
    val selectedTimeframe = settingsState.selectedStatsPeriod
    LaunchedEffect(viewModel, selectedTimeframe) {
        viewModel.selectPeriodTab(selectedTimeframe, resetIfSelected = false)
    }

    val mediaEntries = uiState.mediaEntries
    val recentActivity = uiState.recentActivity
    val periodSynced = uiState.selectedPeriodTab == selectedTimeframe
    val periodOffset = if (periodSynced) uiState.periodOffset else 0
    val canGoPrevious = periodSynced && uiState.canGoPrevious
    val canGoNext = periodSynced && uiState.canGoNext

    // Calendar-aligned [start, end] bounds for the active timeframe & offset
    val periodBounds: Pair<LocalDate, LocalDate>? = remember(selectedTimeframe, periodOffset) {
        val today = LocalDate.now()
        when (selectedTimeframe) {
            HistoryTimeframe.DAYS_7 -> {
                val dates = get7DDates(periodOffset, today)
                dates.first() to dates.last()
            }
            HistoryTimeframe.DAYS_30 -> {
                val targetMonth = YearMonth.from(today).plusMonths(periodOffset.toLong())
                targetMonth.atDay(1) to targetMonth.atEndOfMonth()
            }
            HistoryTimeframe.YEAR -> {
                val targetYear = today.year + periodOffset
                LocalDate.of(targetYear, 1, 1) to LocalDate.of(targetYear, 12, 31)
            }
            HistoryTimeframe.ALL_TIME -> null
        }
    }

    // Human-readable label shown between < and >
    val periodLabel: String? = remember(selectedTimeframe, periodOffset, periodBounds) {
        when (selectedTimeframe) {
            HistoryTimeframe.DAYS_7 -> {
                periodBounds?.let { (start, end) ->
                    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                    "${start.format(fmt)} – ${end.format(fmt)}"
                }
            }
            HistoryTimeframe.DAYS_30 -> {
                val targetMonth = YearMonth.from(LocalDate.now()).plusMonths(periodOffset.toLong())
                targetMonth.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
            }
            HistoryTimeframe.YEAR -> {
                (LocalDate.now().year + periodOffset).toString()
            }
            HistoryTimeframe.ALL_TIME -> null
        }
    }

    // Filter activity strictly to active reading/watching sessions within the active calendar period
    val filteredActivity = remember(recentActivity, periodBounds) {
        recentActivity.filter { item ->
            val inRange = if (periodBounds == null) {
                true
            } else {
                val entryDate = item.toEntryLocalDate()
                entryDate != null && !entryDate.isBefore(periodBounds.first) && !entryDate.isAfter(periodBounds.second)
            }
            ProgressSource.fromValue(item.source).isActiveConsumption &&
                    item.delta > 0.0 &&
                    inRange
        }
    }

    // All-time active dates with positive progress for streak calculation
    val allActiveDates = remember(recentActivity) {
        recentActivity
            .filter { ProgressSource.fromValue(it.source).isActiveConsumption && it.delta > 0.0 }
            .map { it.activityDate }
            .toSet()
    }

    val (currentStreak, longestStreak) = remember(allActiveDates) {
        calculateStreaks(allActiveDates)
    }

    // Consumed units grouped by unit name (e.g., Chapters, Episodes, Volumes)
    val consumedByType: Map<String, Double> = remember(filteredActivity) {
        filteredActivity
            .groupBy { it.unitName.ifBlank { "Chapter" } }
            .mapValues { (_, items) -> items.sumOf { it.delta } }
            .filterValues { it > 0.0 }
    }

    // Media added count in the selected calendar period
    val mediaAddedInTimeframe = remember(mediaEntries, periodBounds) {
        if (periodBounds == null) {
            mediaEntries.size
        } else {
            val startStr = periodBounds.first.toString()
            val endStr = periodBounds.second.toString()
            mediaEntries.count { entry ->
                val created = entry.createdAt.take(10)
                created in startStr..endStr
            }
        }
    }

    val completedCount = remember(mediaEntries) {
        mediaEntries.count { it.userStatus == UserStatus.COMPLETED }
    }

    val inProgressCount = remember(mediaEntries) {
        mediaEntries.count { it.userStatus == UserStatus.IN_PROGRESS }
    }

    // Group history items chronologically by date
    val groupedHistory = remember(filteredActivity) {
        filteredActivity.groupBy { it.activityDate }
    }

    // Compute calendar-aligned trend bars
    val trendDays = remember(selectedTimeframe, recentActivity, periodOffset) {
        calculateTrendBars(recentActivity, selectedTimeframe, periodOffset)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Screen Header & Timeframe Filter Chips
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Stats & History",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                SecondaryScrollableTabRow(
                    selectedTabIndex = selectedTimeframe.ordinal,
                    edgePadding = 0.dp,
                    containerColor = Color.Transparent,
                    indicator = {
                        Box(
                            modifier = Modifier
                                .tabIndicatorOffset(selectedTabIndex = selectedTimeframe.ordinal)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .zIndex(-1f)
                        )
                    },
                    divider = {}
                ) {
                    HistoryTimeframe.entries.forEach { timeframe ->
                        val selected = selectedTimeframe == timeframe
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = {
                                        if (selected) {
                                            viewModel.selectPeriodTab(timeframe)
                                        } else {
                                            settings.setSelectedStatsPeriod(timeframe)
                                        }
                                    }
                                )
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) Color.Transparent
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = timeframe.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        summarySection(
            selectedTimeframe = selectedTimeframe,
            periodOffset = periodOffset,
            consumedByType = consumedByType,
            filteredActivity = filteredActivity,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            mediaAddedInTimeframe = mediaAddedInTimeframe,
            mediaEntries = mediaEntries,
            inProgressCount = inProgressCount,
            completedCount = completedCount
        )
        activityPaceSection(
            trendDays = trendDays,
            selectedTimeframe = selectedTimeframe,
            viewModel = viewModel,
            canGoPrevious = canGoPrevious,
            periodLabel = periodLabel,
            canGoNext = canGoNext
        )
        libraryBreakdownSection(mediaEntries)
        historyFeedSection(groupedHistory, mediaEntries, onSelectEntry)
    }
}
