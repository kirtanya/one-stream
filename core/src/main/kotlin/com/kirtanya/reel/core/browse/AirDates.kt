package com.kirtanya.reel.core.browse

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Labels for the "New episodes" row: "Today", "Tomorrow", "Yesterday", else "Fri 24 Oct". */
object AirDates {
    fun label(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }

    /**
     * What the row shows, newest first: episodes out in the last [pastDays] (most recent first),
     * then what's coming (soonest first). One card per show: its newest aired, else its next.
     */
    fun <T> pick(entries: List<T>, date: (T) -> LocalDate, show: (T) -> Any, today: LocalDate, pastDays: Long = 7): List<T> {
        val window = entries.filter { !date(it).isBefore(today.minusDays(pastDays)) }
        val aired = window.filter { !date(it).isAfter(today) }.sortedByDescending(date)
        val coming = window.filter { date(it).isAfter(today) }.sortedBy(date)
        val seen = HashSet<Any>()
        return (aired + coming).filter { seen.add(show(it)) }
    }
}
