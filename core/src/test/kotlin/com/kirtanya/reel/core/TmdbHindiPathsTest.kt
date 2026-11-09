package com.kirtanya.reel.core

import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TmdbPaths
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class TmdbHindiPathsTest {
    @Test fun languageDiscoverIsPopularWithAVoteFloor() {
        val p = TmdbPaths.discoverByLanguage(MediaType.MOVIE)
        assertTrue(p.startsWith("discover/movie?"))
        assertTrue("with_original_language=hi" in p)
        assertTrue("sort_by=popularity.desc" in p)
        assertTrue("vote_count.gte=30" in p)
        assertTrue("with_origin_country" !in p)
        assertTrue(TmdbPaths.discoverByLanguage(MediaType.TV).startsWith("discover/tv?"))
    }

    @Test fun newReleasesCoverTheLast60DaysUpToToday() {
        val p = TmdbPaths.newReleasesByLanguage(LocalDate.of(2026, 11, 9))
        assertTrue("primary_release_date.gte=2026-09-10" in p)
        assertTrue("primary_release_date.lte=2026-11-09" in p)
        assertTrue("with_original_language=hi" in p)
    }

    @Test fun newReleaseWindowCrossesYearEnd() {
        val p = TmdbPaths.newReleasesByLanguage(LocalDate.of(2026, 1, 15))
        assertTrue("primary_release_date.gte=2025-11-16" in p)
    }
}
