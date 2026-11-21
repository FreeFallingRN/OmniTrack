package com.omnitrack.app.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class DateRangePeriod(
    val start: LocalDate,
    val end: LocalDate,
    val periodOffset: Int
)

fun get7DDates(periodOffset: Int = 0, today: LocalDate = LocalDate.now()): List<LocalDate> {
    val currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val targetMonday = currentMonday.plusWeeks(periodOffset.toLong())
    return (0L..6L).map { offset -> targetMonday.plusDays(offset) }
}

fun get30DWeeks(
    periodOffset: Int = 0,
    currentYearMonth: YearMonth = YearMonth.now()
): Map<Int, List<LocalDate>> {
    val targetMonth = currentYearMonth.plusMonths(periodOffset.toLong())
    val daysInMonth = targetMonth.lengthOfMonth()

    val ranges = listOf(
        1 to 1..7,
        2 to 8..14,
        3 to 15..21,
        4 to 22..daysInMonth
    )

    val result = LinkedHashMap<Int, List<LocalDate>>()
    for ((weekNumber, dayRange) in ranges) {
        result[weekNumber] = dayRange.map { day -> targetMonth.atDay(day) }
    }
    return result
}

fun getYearMonths(
    periodOffset: Int = 0,
    currentYear: Int = LocalDate.now().year
): List<YearMonth> {
    val targetYear = currentYear + periodOffset
    return (1..12).map { month -> YearMonth.of(targetYear, month) }
}

fun getAllTimeYears(
    earliestEntryDate: LocalDate?,
    currentDate: LocalDate = LocalDate.now()
): List<Int> {
    if (earliestEntryDate == null) return emptyList()
    val startYear = earliestEntryDate.year
    val endYear = currentDate.year
    if (startYear > endYear) return emptyList()
    return (startYear..endYear).toList()
}