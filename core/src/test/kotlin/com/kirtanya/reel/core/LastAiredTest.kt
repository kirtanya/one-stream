package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TmdbParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `last_episode_to_air` / `next_episode_to_air` → the latest aired regular episode. */
class LastAiredTest {
    private fun show(last: String?, next: String?) = """{"id":1,"name":"Show","first_air_date":"2020-01-01",
        "seasons":[{"season_number":0,"name":"Specials","episode_count":5},
                   {"season_number":1,"name":"Season 1","episode_count":10},
                   {"season_number":2,"name":"Season 2","episode_count":8}]
        ${last?.let { ""","last_episode_to_air":$it""" } ?: ""}
        ${next?.let { ""","next_episode_to_air":$it""" } ?: ""}}"""

    private fun lastAired(last: String?, next: String?) = TmdbParser.parseDetails(show(last, next), MediaType.TV).lastAired

    @Test fun regularLastEpisode() = assertEquals(2 to 3, lastAired("""{"season_number":2,"episode_number":3}""", null))

    @Test fun specialFallsBackToTheEpisodeBeforeNext() =
        assertEquals(2 to 4, lastAired("""{"season_number":0,"episode_number":5}""", """{"season_number":2,"episode_number":5}"""))

    @Test fun specialBeforeANewSeason() {
        // S1 finished, a holiday special aired, S2 is announced (8 episodes) with S2E1 next.
        val aired = lastAired("""{"season_number":0,"episode_number":5}""", """{"season_number":2,"episode_number":1}""")
        assertEquals(1 to Int.MAX_VALUE, aired)
        val seasons = TmdbParser.parseDetails(show(null, null), MediaType.TV).seasons
        assertNull(nextEpisode(seasons, EpisodeNumber(1, 10), aired)) // S2E1 hasn't aired: no Up next
        assertEquals(EpisodeNumber(1, 5), nextEpisode(seasons, EpisodeNumber(1, 4), aired))
    }

    @Test fun unknown() {
        assertNull(lastAired("""{"season_number":0,"episode_number":5}""", null))
        assertNull(lastAired(null, null))
    }
}
