package com.kirtanya.reel.core

import com.kirtanya.reel.core.library.DlAction
import com.kirtanya.reel.core.library.DlKind
import com.kirtanya.reel.core.library.DlPhase
import com.kirtanya.reel.core.library.DownloadButtonState
import com.kirtanya.reel.core.library.DownloadFacts
import com.kirtanya.reel.core.library.RowMark
import com.kirtanya.reel.core.library.TitleDownload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleDownloadTest {
    private val mb = 1L shl 20
    private val gb = 1L shl 30

    private fun torrent(
        done: Long = 0, wanted: Long = 2 * gb, bps: Long = 0, phase: DlPhase = DlPhase.DOWNLOADING,
        meta: Boolean = true, stalled: Int = 0, error: String? = null,
    ) = DownloadFacts(
        key = "abc", http = false, phase = phase, progress = if (wanted > 0) done.toFloat() / wanted else 0f,
        wantedBytes = wanted, doneBytes = done, downBps = bps, error = error, hasMetadata = meta, stalledForSec = stalled,
    )

    private fun http(
        done: Long = 0, wanted: Long = 2 * gb, bps: Long = 0, phase: DlPhase = DlPhase.DOWNLOADING,
        notice: String? = null, error: String? = null, notReady: Boolean = false, disk: Boolean = false, night: Boolean = false,
    ) = DownloadFacts(
        key = "http:1", http = true, phase = phase, progress = if (wanted > 0) done.toFloat() / wanted else 0f,
        wantedBytes = wanted, doneBytes = done, downBps = bps, error = error, notice = notice, notReady = notReady,
        waitingForDisk = disk, waitingForNight = night,
    )

    @Test fun `nothing started offers Download`() {
        val s = TitleDownload.state(null)
        assertEquals(DlKind.IDLE, s.kind)
        assertEquals("Download", s.label)
        assertEquals(DlAction.DOWNLOAD, s.main)
        assertNull(s.bar)
    }

    @Test fun `pressed but not yet created shows Starting and offers nothing so a second press does nothing`() {
        val s = TitleDownload.state(null, starting = true)
        assertEquals(DlKind.STARTING, s.kind)
        assertEquals("Starting…", s.label)
        assertTrue(s.actions.isEmpty())
    }

    @Test fun `a start that failed shows why and lets the user try again`() {
        val s = TitleDownload.state(null, startError = "No space on the disk")
        assertEquals(DlKind.FAILED, s.kind)
        assertEquals("No space on the disk", s.detail)
        assertEquals(DlAction.DOWNLOAD, s.main)
    }

    @Test fun `finding peers and checking files both read as Starting`() {
        val meta = TitleDownload.state(torrent(phase = DlPhase.METADATA, meta = false))
        val check = TitleDownload.state(torrent(phase = DlPhase.CHECKING))
        assertEquals(DlKind.STARTING, meta.kind)
        assertEquals("Finding peers", meta.detail)
        assertEquals("Checking files", check.detail)
    }

    @Test fun `downloading says percent speed and time left with a whole percent bar`() {
        val s = TitleDownload.state(torrent(done = 10 * mb, wanted = 100 * mb, bps = 2_200_000))
        assertEquals(DlKind.DOWNLOADING, s.kind)
        assertEquals("Downloading 10%", s.label)
        assertEquals("2.1 MB/s · under a minute left", s.detail)
        assertEquals(0.10f, s.bar)
        assertEquals(DlAction.PAUSE, s.main)
        assertNull(s.playLabel)
    }

    @Test fun `a bar that moved less than a whole percent compares equal so nothing redraws`() {
        val a = TitleDownload.state(torrent(done = 1000 * mb + 1, wanted = 2000 * mb, bps = 1_000_000))
        val b = TitleDownload.state(torrent(done = 1000 * mb + 2, wanted = 2000 * mb, bps = 1_000_000))
        assertEquals(a, b)
    }

    @Test fun `torrent becomes ready to play once enough is on disk and keeps downloading`() {
        val early = TitleDownload.state(torrent(done = 5 * mb, bps = 1_000_000))
        assertEquals(DlKind.DOWNLOADING, early.kind)
        val ready = TitleDownload.state(torrent(done = 680 * mb, wanted = 2 * gb, bps = 3_000_000))
        assertEquals(DlKind.READY, ready.kind)
        assertEquals("Ready to play", ready.label)
        assertEquals("Play · downloading 33%", ready.playLabel)
        assertEquals(DlAction.PLAY, ready.main)
        assertTrue(DlAction.PAUSE in ready.actions)
        assertTrue(ready.canPlay)
    }

    @Test fun `a small file is ready after half of it, since the fixed head would be more than the file`() {
        val f = torrent(done = 9 * mb, wanted = 10 * mb)
        assertTrue(TitleDownload.readyToPlay(f))
    }

    @Test fun `a torrent without metadata is never ready`() {
        assertFalse(TitleDownload.readyToPlay(torrent(done = 500 * mb, meta = false)))
    }

    @Test fun `http is ready after the head is on disk`() {
        assertFalse(TitleDownload.readyToPlay(http(done = 4 * mb)))
        assertTrue(TitleDownload.readyToPlay(http(done = 40 * mb, bps = 5_000_000)))
        assertEquals(DlKind.READY, TitleDownload.state(http(done = 40 * mb, bps = 5_000_000)).kind)
    }

    @Test fun `held paused failed and not ready downloads are never ready to play`() {
        assertFalse(TitleDownload.readyToPlay(http(done = 500 * mb, notice = "Retrying in 8 s")))
        assertFalse(TitleDownload.readyToPlay(http(done = 500 * mb, disk = true)))
        assertFalse(TitleDownload.readyToPlay(http(done = 500 * mb, night = true)))
        assertFalse(TitleDownload.readyToPlay(http(done = 500 * mb, phase = DlPhase.PAUSED)))
        assertFalse(TitleDownload.readyToPlay(http(done = 500 * mb, error = "Link expired")))
        assertFalse(TitleDownload.readyToPlay(http(done = 500 * mb, notReady = true)))
        assertFalse(TitleDownload.readyToPlay(torrent(done = 500 * mb, stalled = 300)))
    }

    @Test fun `a finished download is Downloaded and plays the local file`() {
        val s = TitleDownload.state(torrent(done = 2 * gb, phase = DlPhase.FINISHED))
        assertEquals(DlKind.DONE, s.kind)
        assertEquals("Downloaded", s.label)
        assertEquals("2.0 GB", s.detail)
        assertEquals(listOf(DlAction.PLAY, DlAction.DELETE), s.actions)
        assertEquals("Play", s.playLabel)
        assertNull(s.bar)
    }

    @Test fun `paused says where it stopped and offers Resume`() {
        val s = TitleDownload.state(torrent(done = 680 * mb, phase = DlPhase.PAUSED))
        assertEquals(DlKind.PAUSED, s.kind)
        assertEquals("Paused at 33%", s.label)
        assertEquals(DlAction.RESUME, s.main)
        assertFalse(s.canPlay)
    }

    @Test fun `waiting always says the reason`() {
        val disk = TitleDownload.state(http(done = mb, disk = true))
        assertEquals(DlKind.WAITING, disk.kind)
        assertEquals("Waiting for the disk", disk.label)
        assertEquals("The USB disk isn't plugged in", disk.detail)

        val night = TitleDownload.state(http(done = mb, night = true, notice = "Waiting for night (01:00)"))
        assertEquals("Waiting for night", night.label)
        assertEquals("Waiting for night (01:00)", night.detail)

        val retry = TitleDownload.state(http(done = mb, notice = "Retrying in 8 s"))
        assertEquals("Retrying", retry.label)
        assertEquals("Retrying in 8 s", retry.detail)

        val peers = TitleDownload.state(torrent(done = mb, stalled = 200))
        assertEquals("Waiting for peers", peers.label)
        assertTrue(peers.detail!!.isNotBlank())
    }

    @Test fun `not ready on the debrid service splits into label and hint with Retry and Change source`() {
        val s = TitleDownload.state(http(notReady = true, error = "Not ready on AllDebrid yet. Try again later or pick another source"))
        assertEquals(DlKind.NOT_READY, s.kind)
        assertEquals("Not ready on AllDebrid yet", s.label)
        assertEquals("Try again later or pick another source", s.detail)
        assertEquals(listOf(DlAction.RETRY, DlAction.CHANGE_SOURCE, DlAction.DELETE), s.actions)
    }

    @Test fun `failed names the reason and offers Retry`() {
        val s = TitleDownload.state(http(phase = DlPhase.ERROR, error = "Link expired"))
        assertEquals(DlKind.FAILED, s.kind)
        assertEquals("Download failed", s.label)
        assertEquals("Link expired", s.detail)
        assertEquals(DlAction.RETRY, s.main)
        assertTrue(DlAction.CHANGE_SOURCE in s.actions)
    }

    @Test fun `eta reads naturally`() {
        assertEquals("under a minute left", TitleDownload.etaLine(30))
        assertEquals("12 min left", TitleDownload.etaLine(12 * 60))
        assertEquals("1 h 05 min left", TitleDownload.etaLine(3900))
        assertEquals("more than a day left", TitleDownload.etaLine(30 * 3600))
    }

    @Test fun `pick prefers finished then healthy then furthest`() {
        val failed = torrent(done = 900 * mb, phase = DlPhase.ERROR, error = "x")
        val slow = torrent(done = 10 * mb)
        val far = torrent(done = 700 * mb)
        val done = torrent(done = 2 * gb, phase = DlPhase.FINISHED)
        assertEquals(far, TitleDownload.pick(listOf(failed, slow, far)))
        assertEquals(done, TitleDownload.pick(listOf(failed, far, done)))
        assertEquals(failed, TitleDownload.pick(listOf(failed)))
        assertNull(TitleDownload.pick(emptyList()))
    }

    @Test fun `every state has a row mark and idle has none`() {
        assertEquals(RowMark.NONE, TitleDownload.mark(DlKind.IDLE))
        assertEquals(RowMark.DONE, TitleDownload.mark(DlKind.DONE))
        assertEquals(RowMark.PROBLEM, TitleDownload.mark(DlKind.FAILED))
        assertEquals(RowMark.PROBLEM, TitleDownload.mark(DlKind.NOT_READY))
        assertEquals(RowMark.READY, TitleDownload.mark(DlKind.READY))
        DlKind.entries.forEach { TitleDownload.mark(it) }
    }

    @Test fun `states groups downloads per item and lets facts win over a pending start`() {
        val far = torrent(done = 700 * mb)
        val slow = torrent(done = 10 * mb)
        val states = TitleDownload.states(
            items = listOf("tv:1:s1e1" to slow, "tv:1:s1e1" to far, "tv:1:s1e2" to torrent(done = 2 * gb, phase = DlPhase.FINISHED)),
            starting = setOf("tv:1:s1e2", "tv:1:s1e3"),
            startErrors = mapOf("tv:1:s1e4" to "No space"),
        )
        assertEquals(DlKind.READY, states.getValue("tv:1:s1e1").kind)
        assertEquals(DlKind.DONE, states.getValue("tv:1:s1e2").kind)
        assertEquals(DlKind.STARTING, states.getValue("tv:1:s1e3").kind)
        assertEquals(DlKind.FAILED, states.getValue("tv:1:s1e4").kind)
        assertEquals(4, states.size)
        assertTrue(TitleDownload.states(emptyList(), emptySet(), emptyMap()).isEmpty())
    }

    @Test fun `season line counts finished and running ones`() {
        fun st(kind: DlKind) = DownloadButtonState(kind, "x")
        assertNull(TitleDownload.seasonLine(listOf(null, st(DlKind.IDLE), st(DlKind.FAILED)), 10))
        assertEquals("3 of 10 downloaded", TitleDownload.seasonLine(List(3) { st(DlKind.DONE) } + List(7) { null }, 10))
        assertEquals("1 of 10 downloaded · 2 downloading", TitleDownload.seasonLine(listOf(st(DlKind.DONE), st(DlKind.DOWNLOADING), st(DlKind.READY)), 10))
        assertEquals("1 downloading", TitleDownload.seasonLine(listOf(st(DlKind.PAUSED)), 10))
        assertNull(TitleDownload.seasonLine(listOf(st(DlKind.DONE)), 0))
    }
}
