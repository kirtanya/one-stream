package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.AutoDownloadSettings
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.DownloadedSet
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.core.library.MyListDownload
import com.kirtanya.reel.core.library.OnAddToList
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MyListDownloadTest {
    private val aired = (1..3).map { EpisodeNumber(1, it) } + (1..2).map { EpisodeNumber(2, it) }

    @Test fun showNotStartedDownloadsTheFirstEpisodeOnly() {
        assertEquals(EpisodeNumber(1, 1), MyListDownload.episodeFor(7, aired) { false })
    }

    @Test fun startedShowDownloadsTheNextUnwatchedEpisode() {
        val watched = setOf("tv:7:s1e1", "tv:7:s1e2")
        assertEquals(EpisodeNumber(1, 3), MyListDownload.episodeFor(7, aired, watched::contains))
        // Crosses into the next season.
        val s1 = (1..3).map { "tv:7:s1e$it" }.toSet()
        assertEquals(EpisodeNumber(2, 1), MyListDownload.episodeFor(7, aired, s1::contains))
    }

    @Test fun skippedEpisodesBeforeTheLastWatchedOneStayBehind() {
        // Watched S1E2 only: carries on after it, like Continue watching, and does not go back to E1.
        assertEquals(EpisodeNumber(1, 3), MyListDownload.episodeFor(7, aired) { it == "tv:7:s1e2" })
    }

    @Test fun caughtUpOrNothingAiredGetsNothing() {
        val all = aired.map { "tv:7:s${it.season}e${it.episode}" }.toSet()
        assertNull(MyListDownload.episodeFor(7, aired, all::contains))
        assertNull(MyListDownload.episodeFor(7, emptyList()) { false })
    }

    private fun torrent(h: String, quality: Int = 1080, extra: String? = null) =
        Source(SourceKind.TORRENT, "x$h", "p", Quality(quality), infoHash = h, extra = extra)

    @Test fun bestIsTheRankersFirstDownloadablePick() {
        val service = Source(SourceKind.EXTERNAL, "Netflix", "JustWatch", Quality(1080), externalUrl = "https://n")
        val trailer = torrent("t", extra = "Trailer")
        val noLink = Source(SourceKind.DIRECT, "no url", "p", Quality(1080))
        val good = torrent("good")
        // The ranker's order is kept: the first source that can really be downloaded wins, whatever its quality.
        assertEquals(good, MyListDownload.bestToDownload(listOf(service, trailer, noLink, good, torrent("second", 2160))))
        assertNull(MyListDownload.bestToDownload(listOf(service, trailer, noLink)))
    }

    @Test fun bestOnUsbSkipsA4GibFileWhenASmallerOneIsCloseInQuality() {
        fun t(h: String, q: Int, gb: Long) = torrent(h, q).copy(sizeBytes = gb * 1024 * 1024 * 1024, magnet = "magnet:?xt=urn:btih:" + "ab".repeat(20))
        val ranked = listOf(t("big", 2160, 9), t("uhdSmall", 2160, 3), t("fhd", 1080, 2))
        assertEquals("big", MyListDownload.bestToDownload(ranked)?.infoHash)
        assertEquals("uhdSmall", MyListDownload.bestToDownload(ranked, underFatLimit = true)?.infoHash)
        // Two steps down is too far: keep the big one (the placement then sends it to internal or refuses it).
        val far = listOf(t("big", 2160, 9), t("sd", 1080, 1))
        assertEquals("big", MyListDownload.bestToDownload(far, underFatLimit = true)?.infoHash)
    }

    private fun dl(origin: DownloadOrigin, finished: Boolean) = KnownDownload("h${origin.hashCode()}", origin, finished)
    private val film = DownloadOrigin("movie", 550, null, null, "Fight Club")
    private fun ep(season: Int, episode: Int) = DownloadOrigin("tv", 7, season, episode, "Show")

    @Test fun anExistingDownloadStopsASecondOne() {
        assertTrue(MyListDownload.alreadyDownloading(film, listOf(dl(film, finished = false))))
        assertTrue(MyListDownload.alreadyDownloading(ep(1, 1), listOf(dl(ep(1, 1), finished = true))))
        assertFalse(MyListDownload.alreadyDownloading(ep(1, 2), listOf(dl(ep(1, 1), finished = true))))
        assertFalse(MyListDownload.alreadyDownloading(film, listOf(dl(ep(1, 1), finished = true))))
    }

    @Test fun onlyCompleteDownloadsGetTheBadge() {
        val set = DownloadedSet.of(listOf(dl(film, finished = true), dl(ep(1, 1), finished = true), dl(ep(1, 2), finished = false)))
        assertTrue(set.movie(550))
        assertFalse(set.movie(551))
        assertTrue(set.episode(7, EpisodeNumber(1, 1)))
        // Still downloading: no badge yet.
        assertFalse(set.episode(7, EpisodeNumber(1, 2)))
        assertTrue(set.anyEpisode(7))
        assertFalse(set.anyEpisode(8))
    }

    @Test fun aShowIsCompleteOnlyWhenEveryAiredEpisodeIs() {
        val some = DownloadedSet.of(listOf(dl(ep(1, 1), true), dl(ep(1, 2), true)))
        val twoAired = listOf(EpisodeNumber(1, 1), EpisodeNumber(1, 2))
        assertTrue(some.show(7, twoAired))
        assertFalse(some.show(7, twoAired + EpisodeNumber(1, 3)))
        assertFalse(some.show(7, emptyList()))
        assertTrue(DownloadedSet.EMPTY.isEmpty)
    }

    @Test fun settingDefaultsToAskAndOldSavedSettingsStillLoad() {
        assertEquals(OnAddToList.ASK, AutoDownloadSettings().onAddToList)
        val json = Json { ignoreUnknownKeys = true }
        // A copy saved before the setting existed.
        val old = json.decodeFromString(AutoDownloadSettings.serializer(), """{"enabled":true,"keepNext":3}""")
        assertEquals(OnAddToList.ASK, old.onAddToList)
        assertTrue(old.enabled)
        val saved = json.encodeToString(AutoDownloadSettings.serializer(), AutoDownloadSettings(onAddToList = OnAddToList.BEST))
        assertEquals(OnAddToList.BEST, json.decodeFromString(AutoDownloadSettings.serializer(), saved).onAddToList)
    }
}
