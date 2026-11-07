package com.kirtanya.reel.core.browse

import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The quick facts a glance needs (TV search's focused-title overlay): what it is and how long, and for a show how much
 * of it there is and whether more is coming. Plain strings, no Android types.
 */
object TitleFacts {
    /** "Film · 2024 · 2h 46m · UA" / "Series · 2019 · 3 seasons · 24 episodes · 16+". */
    fun basics(d: TitleDetails): List<String> {
        val s = d.summary
        return if (s.ref.type == MediaType.MOVIE) {
            listOfNotNull("Film", s.year?.toString(), d.runtimeMin?.takeIf { it > 0 }?.let(::runtime), d.certification)
        } else {
            val regular = d.seasons.filter { it.number > 0 }
            val episodes = regular.sumOf { it.episodeCount }
            listOfNotNull(
                "Series", s.year?.toString(),
                regular.size.takeIf { it > 0 }?.let { plural(it, "season") },
                episodes.takeIf { it > 0 }?.let { plural(it, "episode") },
                d.certification,
            )
        }
    }

    /**
     * Shows only: "Ongoing · latest S3 E5 · next S3 E6 on 12 Nov", "Ongoing · latest S3 E5", "Ended", "Cancelled";
     * null for films or when TMDB says nothing.
     */
    fun airing(d: TitleDetails, today: LocalDate = LocalDate.now()): String? {
        if (d.summary.ref.type != MediaType.TV) return null
        val latest = d.lastAiredEpisode?.label ?: d.lastAired?.let { (s, e) -> "S$s E$e" }
        return when (d.status) {
            "Ended" -> "Ended"
            "Canceled" -> "Cancelled"
            "Returning Series", "In Production", "Planned", "Pilot" -> {
                val next = d.nextAiring?.let { n ->
                    val date = n.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    "next ${n.label}" + (date?.let { " " + day(it, today) } ?: "")
                }
                listOfNotNull("Ongoing", latest?.let { "latest $it" }, next).joinToString(" · ")
            }
            else -> null
        }
    }

    private fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> "on " + date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM yyyy", Locale.ENGLISH))
    }

    private fun runtime(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m".removeSuffix(" 0m") else "${min}m"

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}
