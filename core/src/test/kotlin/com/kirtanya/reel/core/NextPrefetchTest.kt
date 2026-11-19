package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.homedisk.WaitReason
import com.kirtanya.reel.core.library.BufferWatch
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.KnownDownload
import com.kirtanya.reel.core.library.NextPrefetch
import com.kirtanya.reel.core.library.PrefetchFacts
import com.kirtanya.reel.core.library.PrefetchPick
import com.kirtanya.reel.core.library.PrefetchStep
import com.kirtanya.reel.core.library.PrefetchWhy
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.GiB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NextPrefetchTest {
    private val next = EpisodeNumber(1, 4)
    private val ready = PrefetchFacts(
        enabled = true, isEpisode = true, tmdbId = 99, next = next, downloads = emptyList(), lastTryAgoMs = null,
        downloadVerdict = P2pVerdict.Allow, nightHolds = false, bufferHealthy = true,
    )

    private fun why(f: PrefetchFacts): PrefetchWhy? = when (val s = NextPrefetch.check(f)) {
        PrefetchStep.Go -> null
        is PrefetchStep.Wait -> s.why
        is PrefetchStep.Skip -> s.why
    }

    private fun download(show: Int, s: Int, e: Int, finished: Boolean = false) =
        KnownDownload("h$s$e", DownloadOrigin("tv", show, s, e, "x"), finished)

    @Test fun startsWhenEverythingIsFine() = assertEquals(PrefetchStep.Go, NextPrefetch.check(ready))

    @Test fun offMeansNothing() = assertEquals(PrefetchStep.Skip(PrefetchWhy.OFF), NextPrefetch.check(ready.copy(enabled = false)))

    @Test fun neverForMoviesOrRawFiles() {
        assertEquals(PrefetchWhy.NOT_EPISODE, why(ready.copy(isEpisode = false, next = null)))
        assertEquals(PrefetchWhy.NOT_EPISODE, why(ready.copy(tmdbId = null)))
    }

    @Test fun neverForTheLastEpisode() = assertEquals(PrefetchWhy.LAST_EPISODE, why(ready.copy(next = null)))

    @Test fun skipsWhenTheEpisodeIsAlreadyDownloadedOrDownloading() {
        assertEquals(PrefetchWhy.ALREADY_HAVE, why(ready.copy(downloads = listOf(download(99, 1, 4, finished = true)))))
        assertEquals(PrefetchWhy.ALREADY_HAVE, why(ready.copy(downloads = listOf(download(99, 1, 4)))))
    }

    @Test fun otherEpisodesAndOtherShowsDoNotCount() {
        assertEquals(null, why(ready.copy(downloads = listOf(download(99, 1, 3), download(99, 1, 5), download(7, 1, 4)))))
    }

    @Test fun doesNotRetryTheSameEpisodeStraightAway() {
        assertEquals(PrefetchWhy.RECENT_TRY, why(ready.copy(lastTryAgoMs = 60_000)))
        assertEquals(null, why(ready.copy(lastTryAgoMs = NextPrefetch.RETRY_AFTER_MS)))
    }

    @Test fun followsTheNetworkRuleAndNeverAsks() {
        for (v in listOf(
            P2pVerdict.Wait(WaitReason.MOBILE_DATA, canStartAnyway = true),
            P2pVerdict.Wait(WaitReason.NO_NETWORK),
            P2pVerdict.Ask("fp"),
            P2pVerdict.Block(WaitReason.WORK_NETWORK),
        )) {
            assertEquals(PrefetchStep.Wait(PrefetchWhy.NETWORK), NextPrefetch.check(ready.copy(downloadVerdict = v)), "$v")
        }
    }

    @Test fun respectsTheOvernightWindow() = assertEquals(PrefetchStep.Wait(PrefetchWhy.NIGHT_WINDOW), NextPrefetch.check(ready.copy(nightHolds = true)))

    @Test fun waitsForAHealthyBuffer() = assertEquals(PrefetchStep.Wait(PrefetchWhy.BUFFER), NextPrefetch.check(ready.copy(bufferHealthy = false)))

    @Test fun anExistingDownloadBeatsTheNetworkWait() {
        // Nothing to wait for: the episode is already covered.
        val f = ready.copy(downloads = listOf(download(99, 1, 4)), downloadVerdict = P2pVerdict.Ask("fp"), bufferHealthy = false)
        assertEquals(PrefetchStep.Skip(PrefetchWhy.ALREADY_HAVE), NextPrefetch.check(f))
    }

    // ---- source choice

    // The engine needs real 40-hex hashes: "h1" → "1111…".
    private fun hex(name: String) = name.map { "%x".format(it.code % 16) }.joinToString("").padEnd(40, '0')

    private fun torrent(provider: String, res: Int, hash: String, binge: String? = null, size: Long? = null) =
        Source(SourceKind.TORRENT, "rel $provider $res", provider, Quality(resolution = res), infoHash = hex(hash), bingeGroup = binge, sizeBytes = size)

    private fun direct(provider: String, res: Int) =
        Source(SourceKind.DIRECT, "d", provider, Quality(resolution = res), url = "https://x/$provider/$res")

    private fun chosen(p: PrefetchPick) = assertIs<PrefetchPick.Chosen>(p).source

    @Test fun picksLikeAutoplayNext() {
        val previous = torrent("B", 720, "aaa", binge = "grp")
        val ranked = listOf(torrent("A", 2160, "h1"), torrent("B", 1080, "h2", binge = "grp"), torrent("A", 1080, "h3"))
        assertEquals(hex("h2"), chosen(NextPrefetch.pick(previous, ranked, emptySet())).infoHash)
        // No history (resumed from nothing): the top-ranked one.
        assertEquals(hex("h1"), chosen(NextPrefetch.pick(null, ranked, emptySet())).infoHash)
    }

    @Test fun neverAddsAFileToATorrentTheEngineAlreadyHolds() {
        // The season pack being streamed is the same-release match; fall back to the next best, not the pack.
        val previous = torrent("A", 1080, "pack")
        val ranked = listOf(torrent("A", 1080, "pack"), torrent("A", 1080, "other"), torrent("B", 1080, "third"))
        assertEquals(hex("other"), chosen(NextPrefetch.pick(previous, ranked, setOf(hex("pack")))).infoHash)
        assertEquals(PrefetchPick.None(PrefetchWhy.NO_SOURCE), NextPrefetch.pick(previous, listOf(torrent("A", 1080, "pack")), setOf(hex("pack"))))
    }

    @Test fun heldHashesAreMatchedWithoutCaseAndFromTheMagnet() {
        val viaMagnet = Source.fromMagnet("magnet:?xt=urn:btih:" + "AB".repeat(20), "t", "p", Quality(resolution = 1080))!!
        assertEquals("ab".repeat(20), NextPrefetch.hashOf(viaMagnet))
        assertEquals(PrefetchPick.None(PrefetchWhy.NO_SOURCE), NextPrefetch.pick(null, listOf(viaMagnet), setOf("ab".repeat(20))))
    }

    @Test fun aLinkInsteadOfATorrentDownloadsNothing() {
        assertEquals(PrefetchPick.None(PrefetchWhy.NOT_TORRENT), NextPrefetch.pick(direct("D", 1080), listOf(direct("D", 1080), torrent("A", 1080, "h")), emptySet()))
    }

    @Test fun noSourceAtAll() {
        assertEquals(PrefetchPick.None(PrefetchWhy.NO_SOURCE), NextPrefetch.pick(null, emptyList(), emptySet()))
        val ext = Source(SourceKind.EXTERNAL, "e", "svc", Quality(), externalUrl = "https://x")
        assertEquals(PrefetchPick.None(PrefetchWhy.NO_SOURCE), NextPrefetch.pick(null, listOf(ext), emptySet()))
    }

    // ---- room

    @Test fun roomKeepsTheStorageFloor() {
        val s = torrent("A", 1080, "h", size = 2 * GiB)
        assertTrue(NextPrefetch.fits(s, 3 * GiB, FormFactor.TV))      // 3 − 1 floor = 2
        assertFalse(NextPrefetch.fits(s, 3 * GiB - 1, FormFactor.TV))
        assertFalse(NextPrefetch.fits(s, 3 * GiB, FormFactor.PHONE))  // phone floor is 2 GiB
        assertTrue(NextPrefetch.fits(s, 4 * GiB, FormFactor.PHONE))
    }

    @Test fun unknownSizeAssumesAPackSizedFile() {
        val s = torrent("A", 1080, "h")
        assertFalse(NextPrefetch.fits(s, 3 * GiB, FormFactor.TV))
        assertTrue(NextPrefetch.fits(s, 4 * GiB, FormFactor.TV))
    }
    @Test fun onUsbABigPickIsSwappedForASmallerOneAtTheSameOrLowerQuality() {
        val big = torrent("A", 1080, "big", size = 5 * GiB)
        val small = torrent("B", 1080, "small", size = 2 * GiB)
        val ranked = listOf(big, small)
        assertEquals(hex("big"), chosen(NextPrefetch.pick(null, ranked, emptySet())).infoHash)
        assertEquals(hex("small"), chosen(NextPrefetch.pick(null, ranked, emptySet(), underFatLimit = true)).infoHash)
        // No swap when nothing smaller is left at that quality: the big one stays and the placement decides.
        assertEquals(hex("big"), chosen(NextPrefetch.pick(null, listOf(big), emptySet(), underFatLimit = true)).infoHash)
    }
}

class BufferWatchTest {
    private val ahead = NextPrefetch.MIN_AHEAD_MS
    private val hold = NextPrefetch.HOLD_MS

    @Test fun notHealthyBeforeAnySample() = assertFalse(BufferWatch().healthy(0))

    @Test fun healthyOnlyAfterTheHoldTime() {
        val w = BufferWatch()
        w.sample(1_000, true, ahead)
        assertFalse(w.healthy(1_000 + hold - 1))
        assertTrue(w.healthy(1_000 + hold))
    }

    @Test fun aThinBufferNeverCounts() {
        val w = BufferWatch()
        w.sample(0, true, ahead - 1)
        w.sample(hold * 2, true, ahead - 1)
        assertFalse(w.healthy(hold * 3))
    }

    @Test fun aStallRestartsTheWait() {
        val w = BufferWatch()
        w.sample(0, true, ahead)
        w.sample(hold, true, ahead)
        assertTrue(w.healthy(hold))
        w.sample(hold + 10_000, false, 0)           // rebuffering
        assertFalse(w.healthy(hold + 10_000))
        w.sample(hold + 20_000, true, ahead)
        assertFalse(w.healthy(hold + 20_000 + hold - 1))
        assertTrue(w.healthy(hold + 20_000 + hold))
    }

    @Test fun aSeekThatEmptiesTheBufferRestartsTheWait() {
        val w = BufferWatch()
        w.sample(0, true, ahead)
        w.sample(10_000, true, 2_000)
        assertFalse(w.healthy(hold))
    }

}
