package com.kirtanya.reel.core

import com.kirtanya.reel.core.launcher.LauncherApi
import com.kirtanya.reel.core.launcher.LauncherApi.Query
import com.kirtanya.reel.core.launcher.LauncherCaller
import com.kirtanya.reel.core.launcher.LauncherLink
import com.kirtanya.reel.core.library.WatchlistItem
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbParser
import com.kirtanya.reel.core.tmdb.TmdbPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherApiTest {
    private fun params(vararg kv: Pair<String, String>): (String) -> String? = mapOf(*kv)::get

    @Test fun parsesQueries() {
        assertEquals(Query.Info, LauncherApi.parse("/info", params()))
        assertEquals(Query.Search("dune", 20), LauncherApi.parse("/search", params("q" to " dune ")))
        assertEquals(Query.Search("", 5), LauncherApi.parse("/search", params("limit" to "5")))
        assertEquals(Query.Continue(20), LauncherApi.parse("/continue", params("limit" to "-3")))
        assertEquals(Query.Watchlist(40), LauncherApi.parse("watchlist", params("limit" to "abc")))
        assertEquals(Query.Watchlist(LauncherApi.MAX_LIMIT), LauncherApi.parse("/watchlist", params("limit" to "99999")))
        assertEquals(Query.Title(MediaType.TV, "tt0944947", null), LauncherApi.parse("/title", params("type" to "tv", "imdb" to "tt0944947")))
        assertEquals(Query.Title(MediaType.MOVIE, null, 550), LauncherApi.parse("/title", params("type" to "movie", "tmdb" to "550")))
        assertEquals(Query.Unknown, LauncherApi.parse("/title", params("type" to "movie")))
        assertEquals(Query.Unknown, LauncherApi.parse("/title", params("type" to "person", "tmdb" to "1")))
        assertEquals(Query.Unknown, LauncherApi.parse("/title", params("type" to "movie", "imdb" to "nm123456")))
        assertEquals(Query.Unknown, LauncherApi.parse("/nope", params()))
        assertEquals(Query.Unknown, LauncherApi.parse(null, params()))
    }

    @Test fun columnOrderIsTheContract() {
        assertEquals(
            listOf("key", "type", "tmdb", "imdb", "title", "subtitle", "year", "rating", "poster", "backdrop", "progress", "remaining_min", "link"),
            LauncherApi.COLUMNS,
        )
        val row = LauncherApi.titleRow(TitleSummary(TitleRef(MediaType.MOVIE, 550), "Fight Club", 1999, "/p.jpg", "/b.jpg", 8.4, ""), "tt0137523")
        assertEquals(LauncherApi.COLUMNS.size, row.values().size)
        assertEquals(
            listOf("movie:550", "movie", 550L, "tt0137523", "Fight Club", "1999", 1999, 8.4,
                "https://image.tmdb.org/t/p/w342/p.jpg", "https://image.tmdb.org/t/p/w780/b.jpg", -1.0, -1, "onestream://title/movie/550"),
            row.values().toList(),
        )
    }

    @Test fun searchRowsKeepOrderDropDuplicatesAndLimit() {
        val a = TitleSummary(TitleRef(MediaType.TV, 1), "A", null, null, null, null, "")
        val b = TitleSummary(TitleRef(MediaType.MOVIE, 1), "B", 2020, null, null, null, "")
        val rows = LauncherApi.searchRows(listOf(a, b, a), 5)
        assertEquals(listOf("tv:1", "movie:1"), rows.map { it.key })
        assertNull(rows[0].subtitle)
        assertNull(rows[0].poster)
        assertEquals(1, LauncherApi.searchRows(listOf(a, b), 1).size)
    }

    @Test fun watchlistRowsSkipTombstonesNewestFirst() {
        val items = listOf(
            WatchlistItem("movie:1", "Old", 2001, "/p", null, changedAt = 10),
            WatchlistItem("tv:2", "New", null, null, null, changedAt = 20),
            WatchlistItem("movie:3", "Gone", null, null, null, changedAt = 30, removed = true),
            WatchlistItem("bad", "Bad", null, null, null, changedAt = 40),
        )
        val rows = LauncherApi.watchlistRows(items, 40)
        assertEquals(listOf("tv:2", "movie:1"), rows.map { it.key })
        assertEquals("2001", rows[1].subtitle)
        assertEquals("onestream://title/tv/2", rows[0].link)
        assertEquals(1, LauncherApi.watchlistRows(items, 1).size)
    }

    private val src = Source(kind = SourceKind.DIRECT, title = "x", provider = "p", quality = QualityParser.parse("x"), url = "https://x")

    private fun entry(p: Playback, pos: Long, dur: Long, at: Long) = WatchEntry(p, pos, dur, at)

    private val film = Playback("movie:550", "movie:550", "Fight Club", tmdbType = "movie", tmdbId = 550, imdbId = "tt0137523", year = 1999, posterPath = "/p", backdropPath = "/b", source = src)
    private val ep = Playback(
        "tv:1399:s1e3", "tv:1399", "Game of Thrones", subtitle = "S1 E3 · Lord Snow", tmdbType = "tv", tmdbId = 1399,
        episode = EpisodeNumber(1, 3), next = EpisodeNumber(1, 4), stillPath = "/s", source = src,
    )
    private val file = Playback.forTorrentFile("abc", 0, "Some file").copy(source = src)

    @Test fun continueRows() {
        val items = listOf(
            ContinueItem.Resume(entry(ep, 30 * 60_000L, 60 * 60_000L, 300)),
            ContinueItem.Resume(entry(file, 10 * 60_000L, 20 * 60_000L, 250)),
            ContinueItem.UpNext(entry(film.copy(key = "tv:5:s2e9", titleKey = "tv:5", tmdbType = "tv", tmdbId = 5, episode = EpisodeNumber(2, 9)), 1, 1, 200), EpisodeNumber(3, 1)),
            ContinueItem.Resume(entry(film, 90 * 60_000L, 120 * 60_000L + 30_000, 100)),
        )
        val rows = LauncherApi.continueRows(items, 20)
        assertEquals(listOf("tv:1399:s1e3", "tv:5:s3e1", "movie:550"), rows.map { it.key })
        val got = rows[0]
        assertEquals("S1 · E3 · Lord Snow", got.subtitle)
        assertEquals(0.5, got.progress, 1e-6)
        assertEquals(30, got.remainingMin)
        assertEquals("https://image.tmdb.org/t/p/w780/s", got.backdrop)
        assertEquals("onestream://resume/tv:1399:s1e3", got.link)
        assertEquals("S3 · E1", rows[1].subtitle)
        assertEquals(-1.0, rows[1].progress)
        assertEquals(-1, rows[1].remainingMin)
        assertEquals("1999", rows[2].subtitle)
        assertEquals("tt0137523", rows[2].imdb)
        assertEquals(30, rows[2].remainingMin)
        assertEquals(1, LauncherApi.continueRows(items, 1).size)
        // An episode with no name: just the numbers.
        val bare = LauncherApi.continueRows(listOf(ContinueItem.Resume(entry(ep.copy(subtitle = "S1 E3"), 1, 2, 1))), 5)
        assertEquals("S1 · E3", bare.single().subtitle)
    }

    @Test fun findsTheContinueCardForAResumeKey() {
        val resume = ContinueItem.Resume(entry(ep, 30 * 60_000L, 60 * 60_000L, 300))
        val film = ContinueItem.Resume(entry(film, 90 * 60_000L, 120 * 60_000L, 100))
        val items = listOf(resume, film)
        assertEquals(resume, LauncherApi.findContinue(items, "tv:1399:s1e3"))
        // An older episode key, or the bare show: the show's card.
        assertEquals(resume, LauncherApi.findContinue(items, "tv:1399:s1e1"))
        assertEquals(resume, LauncherApi.findContinue(items, "tv:1399"))
        assertEquals(film, LauncherApi.findContinue(items, "movie:550"))
        assertNull(LauncherApi.findContinue(items, "movie:551"))
        assertNull(LauncherApi.findContinue(items, "garbage"))
        assertEquals(TitleRef(MediaType.TV, 1399), LauncherApi.titleOf("tv:1399:s1e3"))
        assertNull(LauncherApi.titleOf("tv:1399:x"))
        assertNull(LauncherApi.titleOf("file:abc:0"))
    }

    @Test fun parsesDeepLinks() {
        assertEquals(LauncherLink.Title(TitleRef(MediaType.MOVIE, 550)), LauncherLink.parse("onestream://title/movie/550"))
        assertEquals(LauncherLink.Title(TitleRef(MediaType.TV, 1399)), LauncherLink.parse("ONESTREAM://title/tv/1399"))
        assertNull(LauncherLink.parse("onestream://title/person/1"))
        assertNull(LauncherLink.parse("onestream://title/movie/abc"))
        assertNull(LauncherLink.parse("onestream://title/movie/1/extra"))
        assertEquals(LauncherLink.Imdb("tt0944947", MediaType.TV), LauncherLink.parse("onestream://imdb/tt0944947?type=tv"))
        assertEquals(LauncherLink.Imdb("tt0137523", MediaType.MOVIE), LauncherLink.parse("onestream://imdb/tt0137523"))
        assertNull(LauncherLink.parse("onestream://imdb/550?type=movie"))
        assertEquals(LauncherLink.Resume("tv:1399:s1e3"), LauncherLink.parse("onestream://resume/tv:1399:s1e3"))
        assertEquals(LauncherLink.Resume("movie:550"), LauncherLink.parse("onestream://resume/movie%3A550"))
        assertNull(LauncherLink.parse("onestream://resume/file:abc:0"))
        assertEquals(LauncherLink.Search("the bear"), LauncherLink.parse("onestream://search?q=the%20bear"))
        assertEquals(LauncherLink.Search("the bear"), LauncherLink.parse("onestream://search?q=the+bear"))
        assertNull(LauncherLink.parse("onestream://search?q="))
        assertNull(LauncherLink.parse("magnet:?xt=urn:btih:abc"))
        assertNull(LauncherLink.parse("onestream://nope"))
        assertNull(LauncherLink.parse("not a uri at all %%"))
        assertNull(LauncherLink.parse(null))
    }

    @Test fun builtLinksRoundTrip() {
        val ref = TitleRef(MediaType.TV, 42)
        assertEquals(LauncherLink.Title(ref), LauncherLink.parse(LauncherLink.title(ref)))
        assertEquals(LauncherLink.Resume("tv:42:s2e5"), LauncherLink.parse(LauncherLink.resume("tv:42:s2e5")))
        assertEquals(LauncherLink.Search("Amélie & co"), LauncherLink.parse(LauncherLink.search("Amélie & co")))
    }

    @Test fun onlyOneLauncherSignedWithItsKeyMayRead() {
        val own = 10_100
        val pkgs = mapOf(10_200 to listOf("com.kirtanya.one_launcher"), 10_300 to listOf("com.kirtanya.one_launcher.tv"), 10_400 to listOf("com.evil"))
        val good = LauncherCaller.certSha256
        val signedWell: (String, ByteArray) -> Boolean = { _, d -> d.contentEquals(good) }
        assertTrue(LauncherCaller.isAllowed(own, own, { null }, { _, _ -> false }))
        assertTrue(LauncherCaller.isAllowed(10_200, own, pkgs::get, signedWell))
        assertTrue(LauncherCaller.isAllowed(10_300, own, pkgs::get, signedWell))
        assertFalse(LauncherCaller.isAllowed(10_400, own, pkgs::get, signedWell))
        // Right name, wrong key (a look-alike sideload).
        assertFalse(LauncherCaller.isAllowed(10_200, own, pkgs::get) { _, _ -> false })
        // Lookups that fail (package not visible, uid gone) never allow.
        assertFalse(LauncherCaller.isAllowed(10_200, own, pkgs::get) { _, _ -> throw IllegalStateException("NameNotFound") })
        assertFalse(LauncherCaller.isAllowed(10_200, own, { throw SecurityException() }, signedWell))
        assertFalse(LauncherCaller.isAllowed(10_500, own, pkgs::get, signedWell))
        assertEquals(32, good.size)
        assertEquals(0x42.toByte(), good[0])
        assertEquals(0x11.toByte(), good[31])
    }

    @Test fun findPrefersTheAskedType() {
        val body = """{"movie_results":[{"id":7,"title":"M","release_date":"2001-01-01"}],"tv_results":[{"id":9,"name":"T","first_air_date":"2011-04-17","vote_average":8.5}]}"""
        assertEquals(TitleRef(MediaType.TV, 9), TmdbParser.parseFind(body, MediaType.TV)?.ref)
        assertEquals(TitleRef(MediaType.MOVIE, 7), TmdbParser.parseFind(body, MediaType.MOVIE)?.ref)
        assertEquals(TitleRef(MediaType.TV, 9), TmdbParser.parseFind("""{"movie_results":[],"tv_results":[{"id":9,"name":"T"}]}""", MediaType.MOVIE)?.ref)
        assertNull(TmdbParser.parseFind("""{"movie_results":[],"tv_results":[]}""", MediaType.MOVIE))
        assertEquals("find/tt0944947?external_source=imdb_id&language=en-US", TmdbPaths.find("tt0944947"))
    }
}
