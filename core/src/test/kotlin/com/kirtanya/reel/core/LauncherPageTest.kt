package com.kirtanya.reel.core

import com.kirtanya.reel.core.launcher.LauncherApi
import com.kirtanya.reel.core.launcher.LauncherApi.Query
import com.kirtanya.reel.core.launcher.LauncherLink
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.launcher.SourceTokens
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.SeasonSummary
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import com.kirtanya.reel.core.tmdb.TmdbParser
import com.kirtanya.reel.core.tmdb.WatchProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherPageTest {
    private fun params(vararg kv: Pair<String, String>): (String) -> String? = mapOf(*kv)::get

    @Test fun parsesPageQueries() {
        assertEquals(Query.Details(MediaType.TV, 1399), LauncherApi.parse("/details", params("type" to "tv", "tmdb" to "1399")))
        assertEquals(Query.Unknown, LauncherApi.parse("/details", params("type" to "tv")))
        assertEquals(Query.Episodes(1399, 0), LauncherApi.parse("/episodes", params("tmdb" to "1399", "season" to "0")))
        assertEquals(Query.Unknown, LauncherApi.parse("/episodes", params("tmdb" to "1399")))
        assertEquals(Query.Sources(MediaType.MOVIE, 550, null, null), LauncherApi.parse("/sources", params("type" to "movie", "tmdb" to "550", "season" to "1")))
        assertEquals(Query.Sources(MediaType.TV, 1399, 1, 3), LauncherApi.parse("/sources", params("type" to "tv", "tmdb" to "1399", "season" to "1", "episode" to "3")))
        assertEquals(Query.Unknown, LauncherApi.parse("/sources", params("type" to "tv", "tmdb" to "1399")))
        assertEquals(Query.Downloads, LauncherApi.parse("/downloads", params()))
    }

    @Test fun columnOrders() {
        assertEquals(LauncherApi.COLUMNS + listOf("overview", "genres", "runtime_min", "seasons", "status", "in_watchlist", "resume_key", "resume_progress"), LauncherPage.DETAILS_COLUMNS)
        assertEquals(listOf("season", "episode", "name", "overview", "still", "air_date", "aired", "progress", "link"), LauncherPage.EPISODE_COLUMNS)
        assertEquals(listOf("token", "name", "provider", "quality", "size_bytes", "seeds", "kind", "languages", "best", "play_link", "downloadable", "hash", "file", "url"), LauncherPage.SOURCE_COLUMNS)
        assertEquals(listOf("key", "title", "subtitle", "progress", "state", "size_bytes", "play_link"), LauncherPage.DOWNLOAD_COLUMNS)
    }

    private val src = Source(kind = SourceKind.DIRECT, title = "x", provider = "p", quality = QualityParser.parse("x"), url = "https://x")
    private val show = TitleRef(MediaType.TV, 1399)

    private fun details(ref: TitleRef) = TitleDetails(
        summary = TitleSummary(ref, "Game of Thrones", 2011, "/p", "/b", 8.4, "Winter is coming."),
        tagline = null, runtimeMin = 60, genres = listOf("Drama", "Fantasy"), certification = null, cast = emptyList(),
        seasons = listOf(SeasonSummary(1, "Season 1", 10, null, 2011), SeasonSummary(2, "Season 2", 10, null, 2012), SeasonSummary(0, "Specials", 3, null, null)),
        imdbId = "tt0944947", providers = WatchProviders.NONE, status = "Ended",
    )

    @Test fun detailsRow() {
        val ep = Playback("tv:1399:s2e4", "tv:1399", "Game of Thrones", tmdbType = "tv", tmdbId = 1399, episode = EpisodeNumber(2, 4), source = src)
        val other = Playback("movie:5", "movie:5", "Other", tmdbType = "movie", tmdbId = 5, source = src)
        val items = listOf(ContinueItem.Resume(WatchEntry(other, 1, 4, 9)), ContinueItem.Resume(WatchEntry(ep, 25, 100, 5)))
        val row = LauncherPage.detailsRow(details(show), inWatchlist = true, continueItems = items)
        assertEquals(LauncherPage.DETAILS_COLUMNS.size, row.size)
        val named = LauncherPage.DETAILS_COLUMNS.zip(row.toList()).toMap()
        assertEquals("tv:1399", named["key"])
        assertEquals("tt0944947", named["imdb"])
        assertEquals("Winter is coming.", named["overview"])
        assertEquals("Drama, Fantasy", named["genres"])
        assertEquals(60, named["runtime_min"])
        assertEquals(2, named["seasons"])
        assertEquals("Ended", named["status"])
        assertEquals(1, named["in_watchlist"])
        assertEquals("tv:1399:s2e4", named["resume_key"])
        assertEquals(0.25, named["resume_progress"] as Double, 1e-6)
        val bare = LauncherPage.DETAILS_COLUMNS.zip(LauncherPage.detailsRow(details(TitleRef(MediaType.MOVIE, 7)), false, items).toList()).toMap()
        assertNull(bare["seasons"])
        assertNull(bare["resume_key"])
        assertEquals(-1.0, bare["resume_progress"])
        assertEquals(0, bare["in_watchlist"])
    }

    @Test fun episodeRows() {
        val eps = listOf(
            Episode(1, 2, "Two", "", "/s2", "2011-04-24", 60),
            Episode(1, 1, "One", "Pilot", "/s1", "2011-04-17", 60),
            Episode(1, 3, "Three", "", null, "2099-01-01", null),
            Episode(1, 4, "Four", "", null, null, null),
        )
        val p = Playback("tv:1399:s1e1", "tv:1399", "GoT", tmdbType = "tv", tmdbId = 1399, episode = EpisodeNumber(1, 1), source = src)
        val history = mapOf("tv:1399:s1e1" to WatchEntry(p, 30, 60, 1), "tv:1399:s1e2" to WatchEntry(p, 59, 60, 1))
        val rows = LauncherPage.episodeRows(1399, eps, "2026-10-21", lastAired = 1 to 3, history = history::get) { s, e -> if (s == 1 && e == 2) "tok12345678" else null }
        assertEquals(listOf(1, 2, 3, 4), rows.map { it[1] })
        val one = LauncherPage.EPISODE_COLUMNS.zip(rows[0].toList()).toMap()
        assertEquals("Pilot", one["overview"])
        assertEquals("https://image.tmdb.org/t/p/w300/s1", one["still"])
        assertEquals(1, one["aired"])
        assertEquals(0.5, one["progress"] as Double, 1e-6)
        assertEquals("onestream://title/tv/1399", one["link"])
        assertEquals(1.0, rows[1][7]) // finished
        assertEquals("onestream://play?source=tok12345678&type=tv&tmdb=1399&season=1&episode=2", rows[1][8])
        assertEquals(0, rows[2][6]) // future date
        assertEquals(-1.0, rows[2][7])
        assertNull(rows[2][4])
        assertEquals(0, rows[3][6]) // no date, after the last aired episode
    }

    private fun torrent(title: String, hash: String, seeds: Int? = 10) = Source(
        kind = SourceKind.TORRENT, title = title, provider = "Torrentio", quality = QualityParser.parse(title), infoHash = hash, seeders = seeds, sizeBytes = 2_000_000_000,
    )

    @Test fun sourceRowsMarkTheScreensBestMatch() {
        val service = Source(kind = SourceKind.EXTERNAL, title = "Netflix", provider = "WatchHub", quality = QualityParser.parse(""), externalUrl = "https://netflix.com/x")
        val ranked = listOf(service, torrent("Movie.2024.1080p.WEB\n👤 50 💾 2 GB 🇮🇳 / 🇬🇧", "a".repeat(40)), src.copy(title = "Direct 720p"))
        val rows = LauncherPage.sourceRows(ranked, listOf("t1aaaaaaaa", "t2aaaaaaaa", "t3aaaaaaaa"), TitleRef(MediaType.MOVIE, 550), null, null)
        fun col(r: Array<Any?>, c: String) = r[LauncherPage.SOURCE_COLUMNS.indexOf(c)]
        assertEquals(listOf(0, 1, 0), rows.map { col(it, "best") })
        assertEquals(listOf("service", "torrent", "direct"), rows.map { col(it, "kind") })
        assertEquals(listOf(0, 1, 1), rows.map { col(it, "downloadable") })
        assertNull(col(rows[0], "quality"))
        assertEquals("1080p", col(rows[1], "quality"))
        assertEquals("Movie.2024.1080p.WEB", col(rows[1], "name"))
        assertEquals("Hindi, English", col(rows[1], "languages"))
        assertEquals(10, col(rows[1], "seeds"))
        assertEquals(-1L, col(rows[2], "size_bytes"))
        assertEquals(-1, col(rows[2], "seeds"))
        assertEquals("onestream://play?source=t2aaaaaaaa&type=movie&tmdb=550", col(rows[1], "play_link"))
        assertNull(LauncherPage.best(listOf(service)))
        assertEquals(listOf(null, "a".repeat(40), null), rows.map { col(it, "hash") })
        assertEquals(listOf(-1, -1, -1), rows.map { col(it, "file") })
        assertEquals(listOf(null, null, "https://x"), rows.map { col(it, "url") })
        assertEquals(LauncherPage.SOURCE_COLUMNS.size, rows[0].size)
    }

    @Test fun languagesFromText() {
        assertEquals("Hindi, English", LauncherPage.languages("Film.2023.Hindi.English.1080p"))
        assertEquals("Tamil, Telugu", LauncherPage.languages("Film [TAM + TEL] 720p"))
        assertEquals("Dual audio", LauncherPage.languages("Film.2023.Dual.Audio.1080p"))
        assertEquals("Multi", LauncherPage.languages("Film MULTI 2160p"))
        assertEquals("Japanese", LauncherPage.languages("🇯🇵 Anime"))
        assertNull(LauncherPage.languages("Film.2023.1080p.WEB-DL.x265"))
        // A word that merely contains a language name doesn't count.
        assertNull(LauncherPage.languages("Englishman.in.New.York"))
    }

    @Test fun downloadStates() {
        assertEquals("downloading", LauncherPage.downloadState("METADATA", false, false))
        assertEquals("downloading", LauncherPage.downloadState("DOWNLOADING", false, false))
        assertEquals("downloading", LauncherPage.downloadState("CHECKING", false, false))
        assertEquals("paused", LauncherPage.downloadState("PAUSED", true, false))
        assertEquals("queued", LauncherPage.downloadState("PAUSED", false, false))
        assertEquals("done", LauncherPage.downloadState("SEEDING", false, false))
        assertEquals("done", LauncherPage.downloadState("FINISHED", false, false))
        assertEquals("failed", LauncherPage.downloadState("DOWNLOADING", false, true))
        assertEquals("failed", LauncherPage.downloadState("ERROR", false, false))
        val hash = "b".repeat(40)
        val row = LauncherPage.downloadRow(hash, "Film", "1 GB of 2 GB", 0.5f, "downloading", 2_000_000_000)
        assertEquals(listOf(hash, "Film", "1 GB of 2 GB", 0.5, "downloading", 2_000_000_000L, "onestream://play?torrent=$hash"), row.toList())
        assertEquals(1.0, LauncherPage.downloadRow(hash, "Film", null, 0.99f, "done", 0)[3])
        assertEquals(-1L, LauncherPage.downloadRow(hash, "Film", null, 0.1f, "queued", 0)[5])
    }

    @Test fun tokensAreHeldLeastRecentlyUsedFirstOut() {
        var n = 0
        val tokens = SourceTokens<String>(capacity = 2) { "token${n++}xyz" }
        val a = tokens.put("a")
        val b = tokens.put("b")
        assertEquals("a", tokens.get(a)) // a is now the most recent
        val c = tokens.put("c")
        assertNull(tokens.get(b))
        assertEquals("a", tokens.get(a))
        assertEquals("c", tokens.get(c))
        tokens.setBest("tv:1:s1e1", c)
        assertEquals(c, tokens.best("tv:1:s1e1"))
        tokens.put("d") // drops a (c was used more recently)
        tokens.put("e") // drops c
        assertNull(tokens.best("tv:1:s1e1"))
        val r1 = SourceTokens.randomToken()
        assertEquals(24, r1.length)
        assertNotEquals(r1, SourceTokens.randomToken())
        assertTrue(SourceTokens.looksLikeToken(r1))
    }

    @Test fun parsesPlayLinks() {
        val link = LauncherLink.play("abcdef123456", show, 1, 3)
        assertEquals(LauncherLink.Play("abcdef123456", null, show, 1, 3), LauncherLink.parse(link))
        assertEquals(LauncherLink.Play("abcdef123456", null, TitleRef(MediaType.MOVIE, 550)), LauncherLink.parse(LauncherLink.play("abcdef123456", TitleRef(MediaType.MOVIE, 550), null, null)))
        val hash = "c".repeat(40)
        assertEquals(LauncherLink.Play(null, hash, null), LauncherLink.parse(LauncherLink.playDownload(hash)))
        // A token with odd characters is ignored; the title fallback still works.
        assertEquals(LauncherLink.Play(null, null, show), LauncherLink.parse("onestream://play?source=../x&type=tv&tmdb=1399"))
        assertNull(LauncherLink.parse("onestream://play"))
        assertNull(LauncherLink.parse("onestream://play?torrent=nothex"))
    }

    @Test fun statusIsParsed() {
        assertEquals("Returning Series", TmdbParser.parseDetails("""{"id":1,"name":"X","status":"Returning Series"}""", MediaType.TV).status)
        assertNull(TmdbParser.parseDetails("""{"id":1,"title":"X"}""", MediaType.MOVIE).status)
    }

    @Test fun actionExtras() {
        assertEquals(TitleRef(MediaType.TV, 1399), LauncherPage.titleRef("tv", 1399))
        assertEquals(TitleRef(MediaType.MOVIE, 550), LauncherPage.titleRef("movie", 550L))
        assertEquals(TitleRef(MediaType.MOVIE, 550), LauncherPage.titleRef("movie", " 550 "))
        assertNull(LauncherPage.titleRef("person", 1))
        assertNull(LauncherPage.titleRef("movie", 0))
        assertNull(LauncherPage.titleRef("movie", 5_000_000_000L))
        assertNull(LauncherPage.titleRef(null, 1))
        val link = LauncherLink.Play("abcdef123456", null, show, 1, 3)
        assertEquals(show, LauncherPage.playFallback(link, tokenKnown = false))
        assertNull(LauncherPage.playFallback(link, tokenKnown = true))
    }
}
