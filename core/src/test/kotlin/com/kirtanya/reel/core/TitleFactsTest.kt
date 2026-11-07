package com.kirtanya.reel.core

import com.kirtanya.reel.core.browse.TitleFacts
import com.kirtanya.reel.core.tmdb.AiringEpisode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.SeasonSummary
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.WatchProviders
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleFactsTest {
    private val today = LocalDate.of(2026, 11, 7)

    private fun details(type: MediaType, runtime: Int? = null, seasons: List<SeasonSummary> = emptyList(), status: String? = null,
                        last: AiringEpisode? = null, next: AiringEpisode? = null) = TitleDetails(
        summary = TitleSummary(TitleRef(type, 1), "X", 2019, null, null, null, ""),
        tagline = null, runtimeMin = runtime, genres = emptyList(), certification = "UA", cast = emptyList(),
        seasons = seasons, imdbId = null, providers = WatchProviders.NONE,
        status = status, lastAiredEpisode = last, nextAiring = next,
    )

    @Test fun film() = assertEquals(listOf("Film", "2019", "2h 46m", "UA"), TitleFacts.basics(details(MediaType.MOVIE, runtime = 166)))

    @Test fun filmOnTheHour() = assertEquals("2h", TitleFacts.basics(details(MediaType.MOVIE, runtime = 120))[2])

    @Test fun showCountsRegularSeasonsAndEpisodes() {
        val s = listOf(SeasonSummary(0, "Specials", 4, null, null), SeasonSummary(1, "S1", 8, null, null), SeasonSummary(2, "S2", 10, null, null))
        assertEquals(listOf("Series", "2019", "2 seasons", "18 episodes", "UA"), TitleFacts.basics(details(MediaType.TV, seasons = s)))
    }

    @Test fun ongoingWithNextDate() {
        val d = details(MediaType.TV, status = "Returning Series", last = AiringEpisode(3, 5, "", "2026-11-01"), next = AiringEpisode(3, 6, "", "2026-11-12"))
        assertEquals("Ongoing · latest S3 E5 · next S3 E6 on 12 Nov", TitleFacts.airing(d, today))
    }

    @Test fun ongoingTomorrow() {
        val d = details(MediaType.TV, status = "Returning Series", next = AiringEpisode(1, 1, "", "2026-11-08"))
        assertEquals("Ongoing · next S1 E1 tomorrow", TitleFacts.airing(d, today))
    }

    @Test fun endedAndFilms() {
        assertEquals("Ended", TitleFacts.airing(details(MediaType.TV, status = "Ended"), today))
        assertNull(TitleFacts.airing(details(MediaType.MOVIE, status = "Released"), today))
    }
}
