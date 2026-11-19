package com.kirtanya.reel.core

import com.kirtanya.reel.core.player.BufferInfoBuilder
import com.kirtanya.reel.core.player.BufferSample
import com.kirtanya.reel.core.player.BufferStage
import com.kirtanya.reel.core.player.GiveUpReason
import com.kirtanya.reel.core.player.PatienceAction
import com.kirtanya.reel.core.player.SourcePatience
import com.kirtanya.reel.core.player.StartSample
import com.kirtanya.reel.core.player.TorrentFallback
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How long a starting torrent is given before the player offers another one, and the info block's numbers. */
class SourcePatienceTest {
    private fun at(t: Long, meta: Boolean = false, peers: Int = 0, bps: Long = 0, done: Long = 0) =
        StartSample(t * 1000, meta, peers, bps, done)

    @Test fun `waits well past the old 8 second hedge`() {
        val p = SourcePatience()
        for (t in 0..24L) assertEquals(PatienceAction.WAIT, p.update(at(t), canSwitch = true).action, "t=$t")
    }

    @Test fun `no info and no peers - countdown at 25 s, switch 5 s later`() {
        val p = SourcePatience()
        for (t in 0..24L) p.update(at(t), true)
        val v = p.update(at(25), true)
        assertEquals(PatienceAction.COUNTDOWN, v.action)
        assertEquals(5, v.secondsLeft)
        assertEquals(GiveUpReason.NO_INFO, v.reason)
        assertEquals(3, p.update(at(27), true).secondsLeft)
        assertEquals(PatienceAction.SWITCH, p.update(at(30), true).action)
    }

    @Test fun `peers connected but no info gets longer`() {
        val p = SourcePatience()
        for (t in 0..39L) assertEquals(PatienceAction.WAIT, p.update(at(t, peers = 3), true).action, "t=$t")
        assertEquals(PatienceAction.COUNTDOWN, p.update(at(40, peers = 3), true).action)
    }

    @Test fun `a source with bytes arriving is never left, however slow`() {
        val p = SourcePatience()
        p.update(at(2, meta = true, peers = 16), true)
        // 729 KB/s then a crawl of 1 KB/s for ten minutes: still downloading.
        for (t in 3..600L) {
            val v = p.update(at(t, meta = true, peers = 16, bps = if (t < 10) 729_000 else 1_000, done = t * 1_000), true)
            assertEquals(PatienceAction.WAIT, v.action, "t=$t")
        }
    }

    @Test fun `file data growing counts even if the rate reads zero`() {
        val p = SourcePatience()
        for (t in 1..120L) assertEquals(PatienceAction.WAIT, p.update(at(t, meta = true, peers = 2, bps = 0, done = t * 4096), true).action)
    }

    @Test fun `metadata but nothing arriving - countdown after 30 s without peers, 45 s with`() {
        val none = SourcePatience()
        none.update(at(1, meta = true), true)
        assertEquals(PatienceAction.WAIT, none.update(at(30, meta = true), true).action)
        val v = none.update(at(31, meta = true), true)
        assertEquals(PatienceAction.COUNTDOWN, v.action)
        assertEquals(GiveUpReason.NO_DATA, v.reason)

        val peers = SourcePatience()
        peers.update(at(1, meta = true, peers = 9), true)
        assertEquals(PatienceAction.WAIT, peers.update(at(45, meta = true, peers = 9), true).action)
        assertEquals(PatienceAction.COUNTDOWN, peers.update(at(46, meta = true, peers = 9), true).action)
    }

    @Test fun `the idle clock for no data starts when data last arrived`() {
        val p = SourcePatience()
        p.update(at(1, meta = true, peers = 4, bps = 500_000, done = 1000), true)
        p.update(at(20, meta = true, peers = 4, bps = 500_000, done = 20_000), true)
        // Stops at t = 20: 45 s of nothing later is t = 65.
        assertEquals(PatienceAction.WAIT, p.update(at(64, meta = true, peers = 4, done = 20_000), true).action)
        assertEquals(PatienceAction.COUNTDOWN, p.update(at(65, meta = true, peers = 4, done = 20_000), true).action)
    }

    @Test fun `data arriving during the countdown cancels it`() {
        val p = SourcePatience()
        for (t in 0..25L) p.update(at(t), true)
        assertEquals(PatienceAction.COUNTDOWN, p.update(at(26), true).action)
        // Info arrives with peers sending bytes.
        assertEquals(PatienceAction.WAIT, p.update(at(27, meta = true, peers = 5, bps = 100_000, done = 5_000), true).action)
        assertEquals(PatienceAction.WAIT, p.update(at(60, meta = true, peers = 5, bps = 100_000, done = 90_000), true).action)
    }

    @Test fun `countdown text keeps the number and unit together`() {
        assertEquals("Trying another source in 5 s", SourcePatience.countdownText(5).replace('\u00A0', ' '))
        assertTrue('\u00A0' in SourcePatience.countdownText(5))
    }

    @Test fun `keep waiting holds 30 s, then asks again`() {
        val p = SourcePatience()
        for (t in 0..25L) p.update(at(t), true)
        assertEquals(PatienceAction.COUNTDOWN, p.update(at(27), true).action)
        p.keepWaiting(27_000)
        for (t in 28..56L) assertEquals(PatienceAction.WAIT, p.update(at(t), true).action, "t=$t")
        val again = p.update(at(57), true)
        assertEquals(PatienceAction.COUNTDOWN, again.action)
        assertEquals(5, again.secondsLeft)
    }

    @Test fun `without another source to go to it only waits, and asks the caller to look`() {
        val p = SourcePatience()
        for (t in 0..14L) assertFalse(p.update(at(t), canSwitch = false, searchable = true).findAlternates, "t=$t")
        assertTrue(p.update(at(15), canSwitch = false, searchable = true).findAlternates)
        val v = p.update(at(40), canSwitch = false, searchable = true)
        assertEquals(PatienceAction.WAIT, v.action)
        // Once one is ready the countdown starts.
        assertEquals(PatienceAction.COUNTDOWN, p.update(at(41), canSwitch = true).action)
    }

    @Test fun `never looks or switches when switching is not allowed (rebuffer, hop limit)`() {
        val p = SourcePatience()
        val v = p.update(at(500), canSwitch = false, searchable = false)
        assertEquals(PatienceAction.WAIT, v.action)
        assertFalse(v.findAlternates)
    }

    // ---- fallback choice

    private fun torrent(n: Int, res: Int?, seeders: Int? = 50, cam: Boolean = false, kind: SourceKind = SourceKind.TORRENT, extra: String? = null) = Source(
        kind = kind, title = "t$n", provider = "Torrentio", quality = Quality(resolution = res, lowQualityRip = cam),
        infoHash = "%040x".format(n), seeders = seeders, extra = extra,
    )

    @Test fun `fallback is another torrent, not a dead swarm, cam, extra or higher resolution`() {
        val cur = torrent(1, 1080)
        val ranked = listOf(
            cur,
            torrent(2, 2160), // bigger
            torrent(3, 1080, seeders = 0), // known dead
            torrent(4, 1080, cam = true),
            torrent(5, 1080, kind = SourceKind.DIRECT),
            torrent(6, 1080, extra = "Trailer"),
            torrent(7, 720),
            torrent(8, 1080),
        )
        assertEquals(listOf("t7", "t8"), TorrentFallback.candidates(cur, ranked, emptySet()).map { it.title })
        assertEquals("t8", TorrentFallback.pick(cur, ranked, tried = setOf(ranked[6].key))?.title)
        assertNull(TorrentFallback.pick(cur, listOf(cur), emptySet()))
    }

    @Test fun `unknown listed seeders is still a candidate, same hash other file is not`() {
        val cur = torrent(1, 1080)
        val sameTorrent = cur.copy(fileIdx = 3)
        assertEquals(listOf("t2"), TorrentFallback.candidates(cur, listOf(sameTorrent, torrent(2, 1080, seeders = null)), emptySet()).map { it.title })
    }

    @Test fun `the chip after a switch names where it went`() {
        val alt = torrent(2, 720).copy(sizeBytes = 1_400_000_000L, provider = "Comet | RD")
        assertEquals("That source wasn't starting. Playing 720p · 1.3 GB · Comet instead", TorrentFallback.switchNote(alt))
    }

    // ---- info block

    private val torrentio = Source(
        kind = SourceKind.TORRENT, title = "x", provider = "Torrentio | Real-Debrid", quality = Quality(resolution = 1080),
        infoHash = "a".repeat(40), seeders = 412, sizeBytes = 1_954_210_119L,
    )

    @Test fun `stage words`() {
        fun stage(meta: Boolean, peers: Int) = BufferInfoBuilder.build(torrentio, null, BufferSample(meta, peers, 0, 0, 0), null, null).stage
        assertEquals(BufferStage.FINDING_PEERS, stage(false, 0))
        assertEquals(BufferStage.GETTING_INFO, stage(false, 4))
        assertEquals(BufferStage.BUFFERING, stage(true, 4))
        assertEquals("Finding peers", BufferStage.FINDING_PEERS.label)
    }

    @Test fun `facts line and stats from the stall report`() {
        val info = BufferInfoBuilder.build(torrentio, null, BufferSample(true, 16, 2, 729 * 1024L, 0), baselineBytes = 0, targetBytes = 40L * 1024 * 1024)
        assertEquals("1080p · 1.8 GB · Torrentio", info.facts)
        assertEquals(listOf("Download" to "729 KB/s", "Peers" to "16", "Seeders" to "412"), info.stats)
    }

    @Test fun `no download yet reads as a dash, live seeds fill in when the listing has none`() {
        val s = torrentio.copy(seeders = null)
        val info = BufferInfoBuilder.build(s, null, BufferSample(false, 0, 3, 0, 0), null, null)
        assertEquals(listOf("Download" to "–", "Peers" to "0", "Seeds" to "3"), info.stats)
        assertNull(info.progress)
    }

    @Test fun `progress to start is measured from where the file was when info arrived`() {
        val mib = 1024L * 1024
        val target = 40 * mib
        val info = BufferInfoBuilder.build(torrentio, null, BufferSample(true, 10, 0, 500_000, 112 * mib), baselineBytes = 100 * mib, targetBytes = target)
        assertEquals(0.3f, info.progress!!, 0.001f)
        assertEquals("12 MB of 40 MB to start", info.progressText)
        val done = BufferInfoBuilder.build(torrentio, null, BufferSample(true, 10, 0, 0, 200 * mib), baselineBytes = 100 * mib, targetBytes = target)
        assertEquals(1f, done.progress!!, 0f)
        assertEquals("Almost ready", done.progressText)
    }

    @Test fun `no bar before info, a seconds bar for a rebuffer`() {
        assertNull(BufferInfoBuilder.build(torrentio, null, BufferSample(false, 4, 0, 0, 0), 0, 1000).progress)
        val re = BufferInfoBuilder.build(torrentio, null, BufferSample(true, 4, 0, 0, 0), null, null, rebufferAheadMs = 2_300)
        assertEquals(0.46f, re.progress!!, 0.001f)
        assertEquals("2 s of 5 s buffered", re.progressText)
    }

    @Test fun `start target is the engine's first fetch plus two seconds of picture, never past the file`() {
        val mib = 1024L * 1024
        val t = BufferInfoBuilder.startTargetBytes(1_954_210_119L, 7_200_000, "1080p")
        assertTrue(t > 3 * mib && t < 5 * mib, "t=$t") // MKV blind tail is 1 MiB since the stream-speed change
        assertEquals(3 * mib, BufferInfoBuilder.startTargetBytes(3 * mib, null, null))
    }
}
