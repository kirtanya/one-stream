package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.playback.hasAired
import com.kirtanya.reel.core.playback.nextEpisode
import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.sources.FetchResponse
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.sources.StremioAddon
import com.kirtanya.reel.core.sources.Subtitles
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.SeasonSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN = 60_000L

private fun src(provider: String = "p", res: Int? = 1080, binge: String? = null, kind: SourceKind = SourceKind.DIRECT) =
    Source(kind, "t", provider, Quality(resolution = res), url = "https://x/$provider/$res/$binge", bingeGroup = binge)

private fun pb(key: String, titleKey: String = key, next: EpisodeNumber? = null) =
    Playback(key = key, titleKey = titleKey, title = key, next = next, source = src())

class NextEpisodeTest {
    private val seasons = listOf(
        SeasonSummary(0, "Specials", 5, null, null),
        SeasonSummary(1, "Season 1", 10, null, null),
        SeasonSummary(2, "Season 2", 8, null, null),
    )

    @Test fun withinSeason() = assertEquals(EpisodeNumber(1, 4), nextEpisode(seasons, EpisodeNumber(1, 3)))
    @Test fun acrossSeasons() = assertEquals(EpisodeNumber(2, 1), nextEpisode(seasons, EpisodeNumber(1, 10)))
    @Test fun lastEpisodeHasNoNext() = assertNull(nextEpisode(seasons, EpisodeNumber(2, 8)))
    @Test fun specialsAreSkipped() = assertEquals(EpisodeNumber(1, 1), nextEpisode(seasons, EpisodeNumber(0, 5)))
    @Test fun unairedEpisodesAreNotNext() {
        // Season 2 is announced with 8 episodes but only 3 have aired.
        assertEquals(EpisodeNumber(2, 3), nextEpisode(seasons, EpisodeNumber(2, 2), lastAired = 2 to 3))
        assertNull(nextEpisode(seasons, EpisodeNumber(2, 3), lastAired = 2 to 3))
        assertNull(nextEpisode(seasons, EpisodeNumber(1, 10), lastAired = 1 to 10))
        // A special as the latest aired episode is ignored.
        assertEquals(EpisodeNumber(2, 5), nextEpisode(seasons, EpisodeNumber(2, 4), lastAired = 0 to 3))
        assertTrue(hasAired(EpisodeNumber(2, 3), 2 to 3) && !hasAired(EpisodeNumber(2, 4), 2 to 3) && hasAired(EpisodeNumber(5, 1), null))
    }
}

class PickNextSourceTest {
    private val ranked = listOf(src("A", 2160), src("B", 1080, binge = "b-1080"), src("A", 1080), src("ext", null, kind = SourceKind.EXTERNAL))

    @Test fun sameBingeGroupWins() = assertEquals("B", pickNextSource(src("B", 720, binge = "b-1080"), ranked)!!.provider)
    @Test fun thenSameProviderAndResolution() = assertEquals(src("A", 1080), pickNextSource(src("A", 1080), ranked))
    @Test fun thenSameProvider() = assertEquals(src("A", 2160), pickNextSource(src("A", 480), ranked))
    @Test fun elseBestPlayable() = assertEquals(src("A", 2160), pickNextSource(src("Z", 1080), ranked))
    @Test fun neverExternal() = assertNull(pickNextSource(null, listOf(src("ext", null, kind = SourceKind.EXTERNAL))))
}

class ContinueWatchingTest {
    private val now = 1_000_000_000L
    private fun entry(pb: Playback, pos: Long, dur: Long = 100 * MIN, ago: Long = 0) = WatchEntry(pb, pos, dur, now - ago)

    @Test fun finishedRules() {
        assertTrue(entry(pb("m"), 93 * MIN).finished)
        assertTrue(entry(pb("m"), 99 * MIN - 30_000).finished) // credits
        assertFalse(entry(pb("m"), 50 * MIN).finished)
        assertFalse(WatchEntry(pb("m"), 5 * MIN, 0, now).finished) // unknown duration
    }

    @Test fun selection() {
        val items = ContinueWatching.select(
            listOf(
                entry(pb("movie:1"), 40 * MIN, ago = 5_000),                                            // resume
                entry(pb("movie:2"), 30_000, ago = 1_000),                                              // barely started → dropped
                entry(pb("movie:3"), 95 * MIN, ago = 2_000),                                            // finished film → dropped
                entry(pb("tv:9:s1e2", "tv:9"), 20 * MIN, ago = 9_000),                                  // older episode of show 9
                entry(pb("tv:9:s1e3", "tv:9", next = EpisodeNumber(1, 4)), 97 * MIN, ago = 3_000),     // newest: finished → up next
                entry(pb("tv:7:s2e8", "tv:7"), 99 * MIN, ago = 4_000),                                  // finished, no next → dropped
                entry(pb("movie:4"), 40 * MIN, ago = 100L * 24 * 60 * MIN),                              // too old
            ),
            now,
        )
        assertEquals(listOf("tv:9:s1e3", "movie:1"), items.map { it.entry.playback.key })
        assertEquals(EpisodeNumber(1, 4), assertIs<ContinueItem.UpNext>(items[0]).next)
        assertIs<ContinueItem.Resume>(items[1])
    }

    @Test fun barelyStartedNewerEpisodeDoesNotHideUpNext() {
        val items = ContinueWatching.select(
            listOf(
                entry(pb("tv:9:s1e3", "tv:9", next = EpisodeNumber(1, 4)), 97 * MIN, ago = 9_000),
                entry(pb("tv:9:s1e4", "tv:9"), 20_000, ago = 1_000), // opened S1E4 for 20 s
            ),
            now,
        )
        assertEquals(EpisodeNumber(1, 4), assertIs<ContinueItem.UpNext>(items.single()).next)
    }

    @Test fun caughtUpShowDropsOutInsteadOfOfferingTheEpisodeJustWatched() {
        val items = ContinueWatching.select(
            listOf(
                entry(pb("tv:9:s1e9", "tv:9", next = EpisodeNumber(1, 10)), 99 * MIN, ago = 9_000),
                entry(pb("tv:9:s1e10", "tv:9"), 99 * MIN, ago = 1_000), // latest aired, finished, nothing after
            ),
            now,
        )
        assertTrue(items.isEmpty())
    }

    @Test fun resumePosition() {
        assertEquals(0, ContinueWatching.resumePositionMs(null))
        assertEquals(0, ContinueWatching.resumePositionMs(entry(pb("m"), 20_000)))
        assertEquals(0, ContinueWatching.resumePositionMs(entry(pb("m"), 95 * MIN)))
        assertEquals(40 * MIN - 5_000, ContinueWatching.resumePositionMs(entry(pb("m"), 40 * MIN)))
    }

    @Test fun playbackRoundTripsThroughJson() {
        val p = Playback.forTorrentFile("a".repeat(40), 2, "Big Buck Bunny 1080p", filename = "bbb.mp4")
        val json = Json.encodeToString(Playback.serializer(), p)
        assertEquals(p, Json.decodeFromString(Playback.serializer(), json))
        assertEquals("file:${"a".repeat(40)}:2", p.key)
        assertEquals(1080, p.source.quality.resolution)
    }
}

class SubtitlesTest {
    private val manifest = StremioAddon.parseManifest(
        """{"id":"org.stremio.opensubtitlesv3","name":"OpenSubtitles v3","types":["movie","series"],"resources":["subtitles"],"idPrefixes":["tt"]}""",
    )
    private val episode = SourceRequest(1399, "tt0944947", MediaType.TV, "GoT", 2011, 1, 1)

    @Test fun languages() {
        assertEquals("en", Subtitles.normalizeLanguage("eng"))
        assertEquals("fr", Subtitles.normalizeLanguage("fre"))
        assertEquals("pt", Subtitles.normalizeLanguage("pob"))
        assertEquals("hi", Subtitles.normalizeLanguage("hin"))
        assertEquals("es", Subtitles.normalizeLanguage("Spanish"))
        assertEquals("en", Subtitles.normalizeLanguage("en"))
        assertEquals("pt", Subtitles.normalizeLanguage("pb"))
    }

    @Test fun urlAndParse() {
        assertTrue(manifest.hasSubtitles && !manifest.hasStreams)
        assertEquals("tt0944947:1:1", manifest.streamIdFor(episode, resource = "subtitles"))
        assertEquals(
            "https://opensubtitles-v3.strem.io/subtitles/series/tt0944947:1:1/videoSize=10&filename=a%20b.mkv.json",
            Subtitles.url("https://opensubtitles-v3.strem.io/manifest.json", episode, "tt0944947:1:1", "a b.mkv", 10),
        )
        val tracks = Subtitles.parse(
            """{"subtitles":[{"id":"1","url":"https://subs/1","lang":"eng","subtitleFileName":"x.srt"},{"id":"2","url":"https://subs/2.vtt","lang":"pob"},{"id":"3"}]}""",
            "OpenSubtitles",
        )
        assertEquals(listOf("en", "pt"), tracks.map { it.language })
        assertEquals(listOf("srt", "vtt"), tracks.map { it.format })
    }

    @Test fun searchMergesAndCapsPerLanguage(): Unit = runBlocking {
        val body = (1..5).joinToString(",", """{"subtitles":[""", "]}") { """{"url":"https://s/$it","lang":"eng"}""" }
        val http = HttpFetcher { r -> FetchResponse(200, r.url, emptyMap(), body.toByteArray()) }
        val tracks = Subtitles.search(episode, listOf("https://opensubtitles-v3.strem.io/manifest.json" to manifest), http, perLanguage = 3)
        assertEquals(3, tracks.size)
    }
}
