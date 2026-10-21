package com.kirtanya.reel.core.tmdb

import java.time.LocalDate

/**
 * "New this week" / "Coming up" for followed shows (My list shows, shows in progress). Built from
 * each show's details (last aired + next announced episode) and its current season's episode list.
 */
object EpisodeCalendar {
    /**
     * Episodes of [shows] airing between [from] and [to] (both included), soonest first; same day →
     * show title. [seasonEpisodes] is each show's (by TMDB id) episode list of the season around now.
     */
    fun entries(shows: List<TitleDetails>, seasonEpisodes: Map<Int, List<Episode>>, from: LocalDate, to: LocalDate): List<CalendarEntry> =
        shows.flatMap { d ->
            val fromSeason = seasonEpisodes[d.summary.ref.id].orEmpty().map { AiringEpisode(it.season, it.number, it.name, it.airDate, it.stillPath) }
            (fromSeason + listOfNotNull(d.lastAiredEpisode, d.nextAiring))
                .distinctBy { it.season to it.episode }
                .filter { it.season > 0 }
                .mapNotNull { e -> date(e.airDate)?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { CalendarEntry(d.summary, e, it) } }
        }.sortedWith(compareBy({ it.date }, { it.show.title }, { it.episode.season }, { it.episode.episode }))

    /** The season to fetch for a show's calendar: the next announced episode's, else the latest aired one's. */
    fun seasonToFetch(d: TitleDetails): Int? = (d.nextAiring ?: d.lastAiredEpisode)?.season?.takeIf { it > 0 }

    /** Shows worth asking about: still running (ended / cancelled shows have nothing coming). */
    fun isFollowable(d: TitleDetails): Boolean = d.summary.ref.type == MediaType.TV && d.status !in setOf("Ended", "Canceled")

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
