package com.omnitrack.app.ui.screens.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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

internal fun LazyListScope.summarySection(
    selectedTimeframe: HistoryTimeframe,
    periodOffset: Int,
    consumedByType: Map<String, Double>,
    filteredActivity: List<ProgressHistoryItem>,
    currentStreak: Int,
    longestStreak: Int,
    mediaAddedInTimeframe: Int,
    mediaEntries: List<MediaEntry>,
    inProgressCount: Int,
    completedCount: Int
) {
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

}

internal fun LazyListScope.activityPaceSection(
    trendDays: List<DailyTrendBar>,
    selectedTimeframe: HistoryTimeframe,
    viewModel: SeriesViewModel,
    canGoPrevious: Boolean,
    periodLabel: String?,
    canGoNext: Boolean
) {
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

}

internal fun LazyListScope.libraryBreakdownSection(mediaEntries: List<MediaEntry>) {
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

}

internal fun LazyListScope.historyFeedSection(
    groupedHistory: Map<String, List<ProgressHistoryItem>>,
    mediaEntries: List<MediaEntry>,
    onSelectEntry: (MediaEntry) -> Unit
) {
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
        "$unitLabel ${formatUnit(item.previousUnit)} → ${formatUnit(item.newUnit)} (+${
            formatUnit(
                delta
            )
        })"
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

