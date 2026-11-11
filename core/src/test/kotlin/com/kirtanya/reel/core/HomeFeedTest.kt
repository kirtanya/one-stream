package com.kirtanya.reel.core

import com.kirtanya.reel.core.tmdb.ContentPreference
import com.kirtanya.reel.core.tmdb.HomeFeed
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbPaths
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeFeedTest {
    private val today = LocalDate.of(2026, 11, 11)

    private fun title(id: Int, type: MediaType = MediaType.MOVIE) = TitleSummary(TitleRef(type, id), "T$id", 2026, null, null, null, "")

    @Test fun `both is exactly the rows Home always had, Hindi rows included`() {
        val rows = HomeFeed.rows(ContentPreference.Both, today)
        assertEquals(
            listOf("Trending this week", "New Hindi releases", "Popular Hindi films", "Hindi shows", "Popular movies", "Trending shows", "Popular in India", "Top rated shows"),
            rows.map { it.title },
        )
        assertEquals(listOf(TmdbPaths.trending(null)), rows[0].requests.map { it.path })
        assertNull(rows[0].requests.single().type)
        assertEquals(TmdbPaths.newReleasesByLanguage(today), rows[1].requests.single().path)
        assertEquals(TmdbPaths.popular(MediaType.MOVIE, "IN"), rows[4].requests.single().path)
        assertEquals(TmdbPaths.trending(MediaType.TV), rows[5].requests.single().path)
        assertEquals(TmdbPaths.discoverFromCountry(MediaType.MOVIE, "IN"), rows[6].requests.single().path)
        assertEquals(TmdbPaths.topRated(MediaType.TV, "IN"), rows[7].requests.single().path)
    }

    @Test fun `hindi rows are all discover queries for hindi originals`() {
        val rows = HomeFeed.rows(ContentPreference.Bollywood, today)
        assertEquals(5, rows.size)
        rows.flatMap { it.requests }.forEach {
            assertTrue(it.path.startsWith("discover/"), it.path)
            assertTrue("with_original_language=hi" in it.path, it.path)
            assertFalse("with_original_language=en" in it.path, it.path)
            assertTrue(it.type != null, "discover answers carry no media_type")
        }
    }

    @Test fun `english rows are all discover queries for english originals`() {
        HomeFeed.rows(ContentPreference.Hollywood, today).flatMap { it.requests }.forEach {
            assertTrue(it.path.startsWith("discover/"), it.path)
            assertTrue("with_original_language=en" in it.path, it.path)
        }
    }

    @Test fun `trending is a recent window ending today`() {
        val trending = HomeFeed.rows(ContentPreference.Bollywood, today)[0]
        assertEquals(listOf(MediaType.MOVIE, MediaType.TV), trending.requests.map { it.type })
        val (film, show) = trending.requests.map { it.path }
        assertTrue("sort_by=popularity.desc" in film)
        assertTrue("primary_release_date.gte=2026-08-13" in film && "primary_release_date.lte=2026-11-11" in film, film)
        assertTrue("air_date.gte=2026-08-13" in show && "air_date.lte=2026-11-11" in show, show)
    }

    @Test fun `top rated rows sort by rating with a vote floor that suits the language`() {
        val hi = HomeFeed.rows(ContentPreference.Bollywood, today).first { it.title == "Top rated films" }.requests.single().path
        val en = HomeFeed.rows(ContentPreference.Hollywood, today).first { it.title == "Top rated films" }.requests.single().path
        assertTrue("sort_by=vote_average.desc" in hi && "vote_count.gte=200" in hi, hi)
        assertTrue("sort_by=vote_average.desc" in en && "vote_count.gte=2000" in en, en)
    }

    @Test fun `a language choice drops the india and extra hindi rows`() {
        val both = HomeFeed.rows(ContentPreference.Both, today).map { it.title }.toSet()
        val hi = HomeFeed.rows(ContentPreference.Bollywood, today).map { it.title }.toSet()
        assertEquals(setOf("Popular in India", "New Hindi releases", "Popular Hindi films", "Hindi shows"), both - hi)
        assertEquals(setOf("Top rated films"), hi - both)
    }

    @Test fun `preference keys round trip and unknown keys mean both`() {
        ContentPreference.entries.forEach { assertEquals(it, ContentPreference.fromKey(it.key)) }
        assertEquals(ContentPreference.Both, ContentPreference.fromKey(null))
        assertEquals(ContentPreference.Both, ContentPreference.fromKey("klingon"))
        assertEquals(listOf(null, "en", "hi"), ContentPreference.entries.map { it.language })
    }

    @Test fun `interleave alternates lists and drops repeats`() {
        val films = listOf(title(1), title(2), title(3))
        val shows = listOf(title(10, MediaType.TV), title(1))  // movie 1 again (same ref) is dropped
        assertEquals(listOf(1, 10, 2, 3), HomeFeed.interleave(listOf(films, shows)).map { it.ref.id })
        assertTrue(HomeFeed.interleave(emptyList()).isEmpty())
        assertEquals(listOf(1, 2, 3), HomeFeed.interleave(listOf(films, emptyList())).map { it.ref.id })
    }
}
