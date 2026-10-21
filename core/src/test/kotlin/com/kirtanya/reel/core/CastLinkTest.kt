package com.kirtanya.reel.core

import com.kirtanya.reel.core.launcher.CastChoice
import com.kirtanya.reel.core.launcher.CastChoice.Decision
import com.kirtanya.reel.core.launcher.CastHint
import com.kirtanya.reel.core.launcher.CastRequest
import com.kirtanya.reel.core.launcher.LauncherLink
import com.kirtanya.reel.core.sources.QualityParser
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastLinkTest {
    private val film = TitleRef(MediaType.MOVIE, 550)
    private val show = TitleRef(MediaType.TV, 1399)
    private val h1 = "a".repeat(40)
    private val h2 = "b".repeat(40)

    private fun cast(link: String) = (LauncherLink.parse(link) as? LauncherLink.Cast)?.request

    @Test fun parsesPlayAndDownload() {
        assertEquals(CastRequest(false, film, null, null, CastHint()), cast("onestream://cast/play?type=movie&tmdb=550"))
        assertEquals(CastRequest(true, show, 1, 3, CastHint()), cast("onestream://cast/download?type=tv&tmdb=1399&season=1&episode=3"))
        // Films ignore season / episode; type and tmdb are needed; shows need both numbers.
        assertEquals(CastRequest(false, film, null, null, CastHint()), cast("onestream://cast/play?type=movie&tmdb=550&season=1&episode=2"))
        assertNull(LauncherLink.parse("onestream://cast/play?tmdb=550"))
        assertNull(LauncherLink.parse("onestream://cast/play?type=movie&tmdb=0"))
        assertNull(LauncherLink.parse("onestream://cast/play?type=tv&tmdb=1399&season=1"))
        assertNull(LauncherLink.parse("onestream://cast/stream?type=movie&tmdb=550"))
        assertNull(LauncherLink.parse("onestream://cast?type=movie&tmdb=550"))
        // Season 0 (specials) is a real season.
        assertEquals(0, cast("onestream://cast/play?type=tv&tmdb=1399&season=0&episode=1")?.season)
    }

    @Test fun parsesHints() {
        val r = cast("onestream://cast/play?type=movie&tmdb=550&hash=${h1.uppercase()}&file=2&url=https%3A%2F%2Fcdn.example%2Fa.mp4%3Fx%3D1")
        assertEquals(CastHint(h1, 2, "https://cdn.example/a.mp4?x=1"), r?.hint)
        // base32 hashes become hex; junk hints are dropped (the link still works).
        val b32 = cast("onestream://cast/play?type=movie&tmdb=550&hash=${"A".repeat(32)}")?.hint?.hash
        assertEquals("0".repeat(40), b32)
        assertEquals(CastHint(), cast("onestream://cast/play?type=movie&tmdb=550&hash=xyz&file=-3&url=javascript:alert(1)")?.hint)
        assertEquals(CastHint(), cast("onestream://cast/play?type=movie&tmdb=550&url=magnet:%3Fxt%3Durn:btih:$h1")?.hint)
    }

    @Test fun linkRoundTrips() {
        val hint = CastHint(h1, 4, "https://cdn.example/a b.mp4")
        val link = CastRequest.link(false, show, 2, 6, hint)
        assertEquals(CastRequest(false, show, 2, 6, hint), cast(link))
        assertEquals("onestream://cast/download?type=movie&tmdb=550", CastRequest.link(true, film))
    }

    private fun torrent(hash: String, file: Int? = null, size: Long = 2_000_000_000, seeds: Int = 50, title: String = "Film.1080p") = Source(
        kind = SourceKind.TORRENT, title = title, provider = "Torrentio", quality = QualityParser.parse(title),
        infoHash = hash, fileIdx = file, sizeBytes = size, seeders = seeds,
    )
    private fun direct(url: String?) = Source(kind = SourceKind.DIRECT, title = "Direct 720p", provider = "Plugin", quality = QualityParser.parse("720p"), url = url)
    private val service = Source(kind = SourceKind.EXTERNAL, title = "Netflix", provider = "WatchHub", quality = QualityParser.parse(""), externalUrl = "https://netflix.com/x")

    @Test fun hintMatching() {
        val pack = listOf(torrent(h1, 0), torrent(h1, 1), torrent(h2), direct("https://cdn/a.mp4"))
        assertEquals(pack[1], CastChoice.matchHint(pack, CastHint(h1, 1)))
        // No file in the hint: the torrent's first listing.
        assertEquals(pack[0], CastChoice.matchHint(pack, CastHint(h1)))
        // Another file of a season pack is a different episode: no match.
        assertNull(CastChoice.matchHint(pack, CastHint(h1, 7)))
        // A source without a file index still matches a hint with one.
        assertEquals(pack[2], CastChoice.matchHint(pack, CastHint(h2, 3)))
        assertEquals(pack[3], CastChoice.matchHint(pack, CastHint(url = "https://cdn/a.mp4")))
        assertNull(CastChoice.matchHint(pack, CastHint(url = "https://cdn/other.mp4")))
        assertNull(CastChoice.matchHint(pack, CastHint()))
        // A hash the search didn't return never plays (the hint only selects).
        assertNull(CastChoice.matchHint(pack, CastHint("c".repeat(40))))
    }

    @Test fun playPicksTheHintIfItFitsElseRecommended() {
        val big = torrent(h1, size = 60_000_000_000)
        val small = torrent(h2)
        val ranked = listOf(service, big, small)
        val fits = { s: Source -> (s.sizeBytes ?: 0) < 10_000_000_000 }
        assertEquals(small, CastChoice.forPlay(ranked, CastHint(h2), fits))
        // Hinted but too big to stream here: the recommended one that fits.
        assertEquals(small, CastChoice.forPlay(ranked, CastHint(h1), fits))
        assertEquals(small, CastChoice.forPlay(ranked, CastHint(), fits))
        // Nothing fits: the ranker's winner (same as the Sources page).
        assertEquals(big, CastChoice.forPlay(listOf(service, big), CastHint(), { false }))
        assertNull(CastChoice.forPlay(listOf(service), CastHint(), { true }))
    }

    @Test fun downloadPicksTheHintElseBestDownloadable() {
        val big = torrent(h1, size = 60_000_000_000)
        val noUrl = direct(null)
        val ranked = listOf(service, noUrl, big, direct("https://cdn/a.mp4"))
        assertEquals(big, CastChoice.forDownload(ranked, CastHint(h1)))
        assertEquals(big, CastChoice.forDownload(ranked, CastHint()))
        assertEquals(ranked[3], CastChoice.forDownload(ranked, CastHint(url = "https://cdn/a.mp4")))
        assertNull(CastChoice.forDownload(listOf(service, noUrl), CastHint()))
    }

    @Test fun decideTakesTheHintEarlyOtherwiseWaits() {
        val a = torrent(h1)
        val b = torrent(h2)
        val play = CastRequest(false, film, null, null, CastHint(h2))
        assertEquals(Decision.Take(b), CastChoice.decide(listOf(a, b), done = false, play) { true })
        assertEquals(Decision.Wait, CastChoice.decide(listOf(a), done = false, play) { true })
        assertEquals(Decision.Take(a), CastChoice.decide(listOf(a), done = true, play) { true })
        assertEquals(Decision.Nothing, CastChoice.decide(listOf(service), done = true, play) { true })
        assertEquals(Decision.Nothing, CastChoice.decide(emptyList(), done = true, play.copy(download = true)) { true })
        // Hinted but too big to stream: no early start, wait for the rest.
        assertEquals(Decision.Wait, CastChoice.decide(listOf(b), done = false, play) { false })
        // Downloads don't care about the streaming limit.
        assertEquals(Decision.Take(b), CastChoice.decide(listOf(b), done = false, play.copy(download = true)) { false })
    }

    @Test fun labels() {
        assertEquals("Fight Club", CastChoice.label("Fight Club", null, null))
        assertEquals("Game of Thrones · S1 E3", CastChoice.label("Game of Thrones", 1, 3))
        assertEquals("Downloading Fight Club on this TV", CastChoice.downloadStarted("Fight Club"))
    }
}
