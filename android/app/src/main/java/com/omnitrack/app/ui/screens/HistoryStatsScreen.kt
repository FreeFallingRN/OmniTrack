package com.omnitrack.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.domain.model.*
import com.omnitrack.app.ui.viewmodel.SeriesViewModel
import com.omnitrack.app.utils.get30DWeeks
import com.omnitrack.app.utils.get7DDates
import com.omnitrack.app.utils.getAllTimeYears
import com.omnitrack.app.utils.getYearMonths
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryStatsScreen(
    viewModel: SeriesViewModel,
    onSelectEntry: (MediaEntry) -> Unit
) {
    val uiState by viewModel.state.collectAsState()
    val mediaEntries = uiState.mediaEntries
    val recentActivity = uiState.recentActivity
    val periodOffset = uiState.periodOffset
    val canGoPrevious = uiState.canGoPrevious
    val canGoNext = uiState.canGoNext

    val selectedTimeframe = uiState.selectedPeriodTab

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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryTimeframe.entries.forEach { timeframe ->
                        val selected = selectedTimeframe == timeframe
                        Surface(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { viewModel.selectPeriodTab(timeframe) }
                                ),
                            shape = CircleShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
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

        item {
            // Compact Summary Metric Strip (2x2 Grid)
            AnimatedContent(
                targetState = selectedTimeframe to periodOffset,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "stats summary transition"
            ) { _ ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Consumed",
                            primaryValue = if (consumedByType.isEmpty()) {
                                "0"
                            } else {
                                consumedByType.entries.joinToString("\n") { (unit, count) ->
                                    "${formatUnit(count)} $unit${if (count != 1.0) "s" else ""}"
                                }
                            },
                            subtitle = "${filteredActivity.map { it.entryId }.distinct().size} unique titles"
                        )

                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Active Streak",
                            primaryValue = "$currentStreak ${if (currentStreak == 1) "day" else "days"}",
                            subtitle = "Best: $longestStreak days"
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Media Added",
                            primaryValue = if (selectedTimeframe == HistoryTimeframe.ALL_TIME) "$mediaAddedInTimeframe" else "+$mediaAddedInTimeframe",
                            subtitle = "Total ${mediaEntries.size} in library"
                        )

                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "In Progress",
                            primaryValue = "$inProgressCount",
                            subtitle = "Completed: $completedCount"
                        )
                    }
                }
            }
        }

        // Activity Pace Card
        if (trendDays.isNotEmpty()) {
            item(key = "activity_pace") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Activity Pace",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (selectedTimeframe != HistoryTimeframe.ALL_TIME) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.stepPeriod(-1) },
                                        enabled = canGoPrevious,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = "Previous period",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (periodLabel != null) {
                                        Text(
                                            text = periodLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.stepPeriod(1) },
                                        enabled = canGoNext,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Next period",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val maxDelta = (trendDays.maxOfOrNull { it.delta } ?: 1.0).coerceAtLeast(1.0)
                        val barWidth = when (selectedTimeframe) {
                            HistoryTimeframe.DAYS_7 -> 16.dp
                            HistoryTimeframe.DAYS_30 -> 28.dp
                            HistoryTimeframe.YEAR -> 10.dp
                            HistoryTimeframe.ALL_TIME -> 32.dp
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            trendDays.forEach { bar ->
                                val heightFraction = if (maxDelta > 0 && bar.delta > 0) {
                                    (bar.delta / maxDelta).toFloat().coerceIn(0.12f, 1f)
                                } else {
                                    0.06f
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    Text(
                                        text = if (bar.delta > 0.0) formatUnit(bar.delta) else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(barWidth)
                                                .fillMaxHeight(heightFraction)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (bar.delta > 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                                )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = bar.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (bar.label == "Today") FontWeight.Bold else FontWeight.Normal,
                                        color = if (bar.label == "Today") {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        },
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Format & Status Breakdown
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Library Breakdown",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (mediaEntries.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Status",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val total = mediaEntries.size.toFloat()
                            val inProgressCountVal = mediaEntries.count { it.userStatus == UserStatus.IN_PROGRESS }
                            val completedCountVal = mediaEntries.count { it.userStatus == UserStatus.COMPLETED }
                            val pausedCount = mediaEntries.count { it.userStatus == UserStatus.PAUSED }
                            val planningCount = mediaEntries.count { it.userStatus == UserStatus.PLANNING }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(CircleShape)
                            ) {
                                if (inProgressCountVal > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(inProgressCountVal / total)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                                if (completedCountVal > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(completedCountVal / total)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.tertiary)
                                    )
                                }
                                if (pausedCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(pausedCount / total)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.secondary)
                                    )
                                }
                                if (planningCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(planningCount / total)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LegendItem("Reading: $inProgressCountVal", MaterialTheme.colorScheme.primary)
                                LegendItem("Completed: $completedCountVal", MaterialTheme.colorScheme.tertiary)
                                LegendItem("Paused: $pausedCount", MaterialTheme.colorScheme.secondary)
                                LegendItem("Planning: $planningCount", MaterialTheme.colorScheme.outlineVariant)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Format",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val total = mediaEntries.size.toFloat()
                            val novels = mediaEntries.count { it.type == MediaType.NOVEL }
                            val comics = mediaEntries.count { it.type == MediaType.COMIC }
                            val anime = mediaEntries.count { it.type == MediaType.ANIME }
                            val movies = mediaEntries.count { it.type == MediaType.MOVIE }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(CircleShape)
                            ) {
                                if (novels > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(novels / total)
                                            .fillMaxHeight()
                                            .background(Color(0xFF6750A4))
                                    )
                                }
                                if (comics > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(comics / total)
                                            .fillMaxHeight()
                                            .background(Color(0xFF006C50))
                                    )
                                }
                                if (anime > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(anime / total)
                                            .fillMaxHeight()
                                            .background(Color(0xFF9C4146))
                                    )
                                }
                                if (movies > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(movies / total)
                                            .fillMaxHeight()
                                            .background(Color(0xFF7D5260))
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (novels > 0) LegendItem("Novels: $novels", Color(0xFF6750A4))
                                if (comics > 0) LegendItem("Comics: $comics", Color(0xFF006C50))
                                if (anime > 0) LegendItem("Anime: $anime", Color(0xFF9C4146))
                                if (movies > 0) LegendItem("Movies: $movies", Color(0xFF7D5260))
                            }
                        }
                    }
                }
            }
        }

        // Chronological History Feed Header
        item {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (groupedHistory.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No reading or watching activity in this period",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            groupedHistory.forEach { (date, items) ->
                item(key = "header_$date") {
                    Text(
                        text = formatHistoryDate(date),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(items, key = { "${it.entryId}_${it.activityDate}_${it.newUnit}" }) { historyItem ->
                    val entry = mediaEntries.firstOrNull { it.id == historyItem.entryId }

                    HistoryItemRow(
                        item = historyItem,
                        entry = entry,
                        onClick = {
                            entry?.let(onSelectEntry)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    primaryValue: String,
    subtitle: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Text(
                text = primaryValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryItemRow(
    item: ProgressHistoryItem,
    entry: MediaEntry?,
    onClick: () -> Unit
) {
    val delta = (item.newUnit - item.previousUnit).coerceAtLeast(0.0)
    val unitLabel = item.unitName.ifBlank { "Ch." }

    val deltaText = if (item.previousUnit > 0.0) {
        "$unitLabel ${formatUnit(item.previousUnit)} → ${formatUnit(item.newUnit)} (+${formatUnit(delta)})"
    } else {
        "+${formatUnit(delta)} ${item.unitName}${if (delta != 1.0) "s" else ""}"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val coverUrl = item.coverUrl ?: entry?.coverUrl
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = item.entryTitle.take(2).uppercase().ifBlank { "TR" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.entryTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = deltaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            val singularTypeLabel = when (item.mediaType) {
                MediaType.NOVEL -> "Novel"
                MediaType.COMIC -> "Comic"
                MediaType.ANIME -> "Anime"
                MediaType.MOVIE -> "Movie"
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = singularTypeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private data class DailyTrendBar(
    val label: String,
    val delta: Double
)

private fun calculateTrendBars(
    activity: List<ProgressHistoryItem>,
    timeframe: HistoryTimeframe,
    periodOffset: Int = 0
): List<DailyTrendBar> {
    val today = LocalDate.now()
    val activeHistory = activity.filter {
        ProgressSource.fromValue(it.source).isActiveConsumption && it.delta > 0.0
    }

    return when (timeframe) {
        HistoryTimeframe.DAYS_7 -> {
            val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            get7DDates(periodOffset, today).map { date ->
                val delta = activeHistory
                    .filter { it.toEntryLocalDate() == date }
                    .sumOf { it.delta }

                DailyTrendBar(
                    label = if (periodOffset == 0 && date == today) "Today" else date.format(dayFormatter),
                    delta = delta
                )
            }
        }
        HistoryTimeframe.DAYS_30 -> {
            get30DWeeks(periodOffset, YearMonth.from(today)).map { (weekNumber, weekDates) ->
                val dateSet = weekDates.toHashSet()
                val delta = activeHistory
                    .filter { item -> item.toEntryLocalDate()?.let { it in dateSet } == true }
                    .sumOf { it.delta }

                DailyTrendBar(
                    label = "W$weekNumber",
                    delta = delta
                )
            }
        }
        HistoryTimeframe.YEAR -> {
            val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
            val monthPrefixFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.getDefault())
            getYearMonths(periodOffset, today.year).map { month ->
                val prefix = month.format(monthPrefixFormatter)
                val delta = activeHistory
                    .filter { it.activityDate.startsWith(prefix) }
                    .sumOf { it.delta }

                DailyTrendBar(
                    label = month.format(monthFormatter),
                    delta = delta
                )
            }
        }
        HistoryTimeframe.ALL_TIME -> {
            val earliestDate = activeHistory
                .mapNotNull { it.toEntryLocalDate() }
                .minOrNull() ?: return emptyList()

            getAllTimeYears(earliestDate, today).map { year ->
                val yearPrefix = year.toString()
                val delta = activeHistory
                    .filter { it.activityDate.startsWith(yearPrefix) }
                    .sumOf { it.delta }

                DailyTrendBar(
                    label = year.toString(),
                    delta = delta
                )
            }
        }
    }
}

private fun calculateStreaks(activeDates: Set<String>): Pair<Int, Int> {
    if (activeDates.isEmpty()) return Pair(0, 0)
    val parsedDates = activeDates.mapNotNull {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }.distinct().sorted()

    if (parsedDates.isEmpty()) return Pair(0, 0)

    var maxStreak = 1
    var currentRun = 1
    for (i in 1 until parsedDates.size) {
        if (parsedDates[i] == parsedDates[i - 1].plusDays(1)) {
            currentRun++
            if (currentRun > maxStreak) maxStreak = currentRun
        } else if (parsedDates[i] != parsedDates[i - 1]) {
            currentRun = 1
        }
    }

    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val lastDate = parsedDates.last()
    var currentStreak = 0
    if (lastDate == today || lastDate == yesterday) {
        var expected = lastDate
        for (i in parsedDates.indices.reversed()) {
            if (parsedDates[i] == expected) {
                currentStreak++
                expected = expected.minusDays(1)
            } else if (parsedDates[i].isBefore(expected)) {
                break
            }
        }
    }

    return Pair(currentStreak, maxStreak)
}

private fun formatHistoryDate(dateStr: String): String {
    return runCatching {
        val date = LocalDate.parse(dateStr)
        val today = LocalDate.now()
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> {
                val formatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
                date.format(formatter)
            }
        }
    }.getOrDefault(dateStr)
}

private fun formatUnit(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}