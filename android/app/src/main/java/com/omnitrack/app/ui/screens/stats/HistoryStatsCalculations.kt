package com.omnitrack.app.ui.screens.stats

import com.omnitrack.app.domain.model.HistoryTimeframe
import com.omnitrack.app.domain.model.ProgressHistoryItem
import com.omnitrack.app.domain.model.ProgressSource
import com.omnitrack.app.utils.get30DWeeks
import com.omnitrack.app.utils.get7DDates
import com.omnitrack.app.utils.getAllTimeYears
import com.omnitrack.app.utils.getYearMonths
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class DailyTrendBar(
    val label: String,
    val delta: Double
)

internal fun calculateTrendBars(
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

internal fun calculateStreaks(activeDates: Set<String>): Pair<Int, Int> {
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

internal fun formatHistoryDate(dateStr: String): String {
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

internal fun formatUnit(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}