package com.kirtanya.reel.core.report

import java.time.LocalDateTime

/**
 * Cuts the app's own logcat (`logcat -d -v threadtime`) down to what a problem report needs:
 * the last few minutes, with anything secret dropped (docs/MACHINE_FIRST_PLAN.md step 1).
 *
 * threadtime lines look like `10-20 14:03:12.345  1234  5678 I ReelStats: ev=start …` — no year.
 * `-v year` adds one (`2026-10-20 …`); both are read.
 */
object LogWindow {
    private val line = Regex("""^(?:(\d{4})-)?(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+\d+\s+\d+\s+[VDIWEFA]\s+(.*?)\s*:\s?(.*)$""")

    /** The parts of one threadtime line that reports use. */
    data class Entry(val time: LocalDateTime, val tag: String, val message: String)

    /**
     * Parses one line. A year-less stamp gets [end]'s year, or the year before when that would put it
     * more than a day after [end] (a December line read on New Year's Day). Null for anything else
     * (`--------- beginning of main`, blank lines).
     */
    fun parse(raw: String, end: LocalDateTime): Entry? {
        val m = line.matchEntire(raw) ?: return null
        val g = m.groupValues
        val month = g[2].toInt()
        val day = g[3].toInt()
        fun at(year: Int): LocalDateTime? = runCatching {
            LocalDateTime.of(year, month, day, g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt() * 1_000_000)
        }.getOrNull()
        val time = g[1].takeIf { it.isNotEmpty() }?.let { at(it.toInt()) }
            ?: at(end.year)?.takeIf { !it.isAfter(end.plusDays(1)) }
            // 29 Feb only exists in leap years: walk back to the last one.
            ?: (1..4).firstNotNullOfOrNull { at(end.year - it) }
            ?: return null
        return Entry(time, g[8], g[9])
    }

    /**
     * Lines stamped within [windowSec] before [end] (and up to a minute after, for clock jitter), in
     * order, minus secret ones ([isSecret]). Unparseable lines go too: they carry no time to check.
     */
    fun filter(lines: List<String>, end: LocalDateTime, windowSec: Long, secrets: Collection<String> = emptyList()): List<String> {
        val from = end.minusSeconds(windowSec)
        val to = end.plusSeconds(60)
        return lines.filter { raw ->
            val e = parse(raw, end) ?: return@filter false
            !e.time.isBefore(from) && !e.time.isAfter(to) && !isSecret(raw, secrets)
        }
    }

    /**
     * Never ship a token: drop a line holding any [secrets] value (the TMDB token), or anything that
     * looks like a credential header. Values shorter than 8 characters are ignored (they'd match
     * ordinary text).
     */
    fun isSecret(raw: String, secrets: Collection<String> = emptyList()): Boolean {
        if (secrets.any { it.length >= 8 && raw.contains(it) }) return true
        val lower = raw.lowercase()
        return MARKERS.any { lower.contains(it) }
    }

    private val MARKERS = listOf("secret", "authorization", "bearer ", "api_key=", "apikey=")

    /** The message part of the lines tagged [tag] (e.g. the last `ReelStats` lines). */
    fun messagesFor(lines: List<String>, tag: String, end: LocalDateTime): List<String> =
        lines.mapNotNull { parse(it, end)?.takeIf { e -> e.tag == tag }?.message }
}
