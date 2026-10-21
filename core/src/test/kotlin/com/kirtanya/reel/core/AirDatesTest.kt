package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.AirDates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AirDatesTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test fun labels() {
        assertEquals("Today", AirDates.label(today, today))
        assertEquals("Tomorrow", AirDates.label(today.plusDays(1), today))
        assertEquals("Yesterday", AirDates.label(today.minusDays(1), today))
        assertEquals("Mon 26 Oct", AirDates.label(LocalDate.of(2026, 10, 26), today))
    }

    @Test fun airedFirstThenComingOneCardPerShow() {
        val e = listOf("a" to 3L, "a" to -2L, "b" to -1L, "c" to -30L, "d" to 1L).map { (show, d) -> show to today.plusDays(d) }
        val picked = AirDates.pick(e, { it.second }, { it.first }, today)
        assertEquals(listOf("b", "a", "d"), picked.map { it.first })
        assertEquals(today.minusDays(2), picked[1].second)
    }
}
