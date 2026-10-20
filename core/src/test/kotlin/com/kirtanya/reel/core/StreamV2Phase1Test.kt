package com.kirtanya.reel.core

import com.kirtanya.reel.core.stream.DiskBackend
import com.kirtanya.reel.core.stream.FileChoice
import com.kirtanya.reel.core.stream.FileIntent
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.KiB
import com.kirtanya.reel.core.stream.MiB
import com.kirtanya.reel.core.stream.PRIO_DEFAULT
import com.kirtanya.reel.core.stream.PRIO_IGNORE
import com.kirtanya.reel.core.stream.RestoreFlags
import com.kirtanya.reel.core.stream.RestoreMeta
import com.kirtanya.reel.core.stream.RestorePolicy
import com.kirtanya.reel.core.stream.SessionTuning
import com.kirtanya.reel.core.stream.StreamStats
import com.kirtanya.reel.core.stream.TorrentFileEntry
import com.kirtanya.reel.core.stream.TorrentKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSpanBytesTest {
    private val pl = 16 * MiB.toInt()

    @Test fun unalignedOffset() {
        val span = FileSpan(offset = 15 * MiB + 900 * KiB, size = 100 * MiB, pieceLength = pl, numPieces = 10)
        assertEquals(0, span.firstPiece)
        assertEquals(0L, span.byteStartOf(0))
        // 15 MiB + 900 KiB is 124 KiB short of the 16 MiB piece edge.
        assertEquals(124 * KiB, span.byteEndOf(0))
        assertEquals(124 * KiB, span.byteStartOf(1))
        assertEquals(0..1, span.piecesForBytes(0, 8 * MiB))
    }

    @Test fun sizeJustPastAPieceEdge() {
        val size = 3L * pl + 100 * KiB
        val span = FileSpan(0, size, pl, 4)
        assertEquals(3, span.lastPiece)
        assertEquals(size, span.byteEndOf(3))
        assertEquals(2..3, span.piecesForBytes(size - 4 * MiB, size))
    }

    @Test fun emptyRangesAreEmpty() {
        val zero = FileSpan(0, 0, pl, 1)
        assertTrue(zero.piecesForBytes(0, 10).isEmpty())
        val span = FileSpan(0, 10 * MiB, pl, 1)
        assertTrue(span.piecesForBytes(5, 5).isEmpty())
        assertTrue(span.piecesForBytes(20 * MiB, 30 * MiB).isEmpty())
    }
}

class TorrentKeyTest {
    private val v1 = "AB".repeat(20)
    private val v2 = "cd".repeat(20)

    @Test fun v1Preferred() = assertEquals(v1.lowercase(), TorrentKey.canonical(v1, v2))
    @Test fun v2OnlyUsesBest() = assertEquals(v2, TorrentKey.canonical(null, v2))
    @Test fun zeroV1UsesBest() = assertEquals(v2, TorrentKey.canonical("0".repeat(40), v2))
}

class FileChoiceTest {
    private val files = listOf(
        TorrentFileEntry(0, "Show/S01E01.mkv", 1000 * MiB),
        TorrentFileEntry(1, "Show/S01E02.mkv", 1100 * MiB),
        TorrentFileEntry(2, "Show/S01E03.mkv", 1200 * MiB),
        TorrentFileEntry(3, "Show/sample.mkv", 5000 * MiB),
    )

    @Test fun requestedIndexWins() =
        assertEquals(1, FileChoice.pickStream(files, 1, null, FileIntent(streamFileIndex = 0))!!.index)

    @Test fun fallsBackToStreamedFile() =
        assertEquals(0, FileChoice.pickStream(files, null, null, FileIntent(streamFileIndex = 0))!!.index)

    @Test fun fallsBackToTheSingleWantedFile() =
        assertEquals(0, FileChoice.pickStream(files, null, null, FileIntent(wantedFiles = listOf(0)))!!.index)

    @Test fun hintOnly() =
        assertEquals(1, FileChoice.pickStream(files, null, null, FileIntent(wantedHints = listOf("S01E02.mkv")))!!.index)

    @Test fun skipsIgnoredFilesWhenGuessing() {
        val pick = FileChoice.pickStream(files, null, null, FileIntent()) { it == 2 }
        assertEquals(1, pick!!.index)
    }

    @Test fun mergeNeverNarrows() {
        val one = FileChoice.merge(FileIntent(), 0, null)
        val two = FileChoice.merge(one, 1, null)
        assertEquals(listOf(0, 1), two.wantedFiles)
        val full = FileChoice.merge(FileIntent(), null, null)
        assertTrue(full.fullTorrent)
        assertTrue(FileChoice.merge(full, 2, null).fullTorrent)
    }

    @Test fun explicitPriorities() {
        assertContentEquals(intArrayOf(4, 4, 4, 4), FileChoice.explicitPriorities(FileIntent(fullTorrent = true), files, 4))
        assertContentEquals(intArrayOf(4, 0, 4, 0), FileChoice.explicitPriorities(FileIntent(wantedFiles = listOf(0, 2)), files, 4))
        assertContentEquals(intArrayOf(0, 4, 0, 0), FileChoice.explicitPriorities(FileIntent(wantedHints = listOf("S01E02")), files, 4))
        // Keep with nothing chosen: everything.
        assertContentEquals(intArrayOf(4, 4, 4, 4), FileChoice.explicitPriorities(FileIntent(), files, 4))
    }

    @Test fun raiseOnlyNeverLowers() =
        assertContentEquals(intArrayOf(PRIO_DEFAULT, PRIO_DEFAULT, PRIO_IGNORE),
            FileChoice.raiseOnly(intArrayOf(4, 0, 0), intArrayOf(0, 4, 0)))
}

class SessionTuningTest {
    @Test fun allCombinationsAreSane() {
        for (ff in FormFactor.entries) for (disk in DiskBackend.entries) {
            val t = SessionTuning.of(ff, disk)
            assertTrue(t.announceToAllTrackers && t.announceToAllTiers)
            assertTrue(t.torrentConnectBoost in 1..255)
            assertTrue(t.maxMetadataSize >= 16 * MiB)
            assertEquals(disk == DiskBackend.POSIX, t.smoothConnects)
        }
    }

    @Test fun tvIsNeverAboveThePhone() {
        val tv = SessionTuning.of(FormFactor.TV, DiskBackend.POSIX)
        val phone = SessionTuning.of(FormFactor.PHONE, DiskBackend.MMAP)
        assertTrue(tv.connectionsLimit <= phone.connectionsLimit)
        assertTrue(tv.connectionSpeed <= phone.connectionSpeed)
        assertTrue(tv.activeLimit <= phone.activeLimit)
        assertEquals(null, tv.maxQueuedDiskBytes)
        assertEquals((8 * MiB).toInt(), phone.maxQueuedDiskBytes)
    }
}

class RestorePolicyTest {
    private val queued = RestoreFlags(autoManaged = true, paused = true, sequential = true)
    private val userPausedLegacy = RestoreFlags(autoManaged = false, paused = true, sequential = false)
    private val running = RestoreFlags(autoManaged = true, paused = false, sequential = false)

    @Test fun streamsComeBackPaused() =
        assertEquals(RestoreFlags(false, true, false), RestorePolicy.restore(running, RestoreMeta(download = false)))

    @Test fun userPausedStaysPaused() =
        assertEquals(RestoreFlags(false, true, false), RestorePolicy.restore(running, RestoreMeta(download = true, userPaused = true)))

    @Test fun legacyPauseIsKeptVerbatim() =
        assertEquals(userPausedLegacy, RestorePolicy.restore(userPausedLegacy, RestoreMeta(download = true)))

    @Test fun queuedComesBackQueued() {
        val r = RestorePolicy.restore(queued, RestoreMeta(download = true))
        assertTrue(r.autoManaged); assertFalse(r.paused); assertFalse(r.sequential)
    }

    @Test fun pausedForStreamResumes() =
        assertEquals(RestoreFlags(true, false, false), RestorePolicy.restore(userPausedLegacy, RestoreMeta(download = true, pausedForStream = true)))

    @Test fun seedingDoneAndSpaceStayPaused() {
        assertTrue(RestorePolicy.restore(running, RestoreMeta(download = true, seedingDone = true)).paused)
        assertTrue(RestorePolicy.restore(running, RestoreMeta(download = true, pausedForSpace = true)).paused)
    }

    @Test fun wasAutoManagedRestored() {
        assertEquals(RestoreFlags(true, false, false), RestorePolicy.restore(userPausedLegacy, RestoreMeta(download = true, wasAutoManaged = true)))
        assertFalse(RestorePolicy.restore(running, RestoreMeta(download = true, wasAutoManaged = false)).autoManaged)
    }
}

class StreamStatsTest {
    @Test fun ttffFromTap() {
        val s = StreamStats(tapMs = 1000)
        s.meta(3000); s.firstPiece(4000); s.firstFrame(6500)
        val line = s.endLine()
        assertTrue("ttm=2000" in line)
        assertTrue("ttfp=3000" in line)
        assertTrue("ttff=5500" in line)
    }

    @Test fun rebuffersExcludeSeekBuffering() {
        val s = StreamStats(0)
        s.firstFrame(10)
        s.bufferingStart(100); s.bufferingEnd(400)        // real rebuffer: 300 ms
        s.seek(1000); s.bufferingStart(1001); s.bufferingEnd(2000); s.readyAfterSeek(2000)
        assertEquals(1, s.rebufferCount)
        assertEquals(300, s.rebufferTotalMs)
        assertEquals(1, s.seekCount)
        assertEquals(1000, s.seekP(50))
    }

    @Test fun bufferingBeforeFirstFrameIsNotARebuffer() {
        val s = StreamStats(0)
        s.bufferingStart(5); s.bufferingEnd(50)
        assertEquals(0, s.rebufferCount)
    }

    @Test fun percentiles() {
        val v = listOf(100L, 200, 300, 400, 1000)
        assertEquals(300, StreamStats.percentile(v, 50))
        assertEquals(1000, StreamStats.percentile(v, 90))
        assertEquals(0, StreamStats.percentile(emptyList(), 50))
    }

    @Test fun lineFormatSkipsNulls() =
        assertEquals("ev=meta t=5 peers=3", StreamStats.line("meta", linkedMapOf("t" to 5, "x" to null, "peers" to 3)))
}

class CachePolicyTest {
    private val gb = com.kirtanya.reel.core.stream.GiB
    private fun e(k: String, bytes: Long, used: Long, stopped: Long = 0, claimed: Boolean = false) =
        com.kirtanya.reel.core.stream.CacheEntry(k, bytes, used, stopped, claimed)

    @Test fun budgets() {
        val P = com.kirtanya.reel.core.stream.FormFactor.PHONE
        val T = com.kirtanya.reel.core.stream.FormFactor.TV
        assertEquals(5 * gb, com.kirtanya.reel.core.stream.CachePolicy.budget(P, 30 * gb, 0))
        assertEquals(20 * gb, com.kirtanya.reel.core.stream.CachePolicy.budget(P, 300 * gb, 0))
        assertEquals(4 * gb, com.kirtanya.reel.core.stream.CachePolicy.budget(T, 10 * gb, 0))
        assertEquals(0, com.kirtanya.reel.core.stream.CachePolicy.budget(T, gb, 0))
    }

    @Test fun justStoppedTitleIsProtected() {
        val out = com.kirtanya.reel.core.stream.CachePolicy.evict(
            listOf(e("old", 3 * gb, used = 1), e("justStopped", 3 * gb, used = 5, stopped = 100)),
            budget = 4 * gb, free = 50 * gb, hardFloor = 2 * gb, explicit = false,
        )
        assertEquals(listOf("old"), out)
    }

    @Test fun protectedGoesOnlyWhenDiskIsCritical() {
        val out = com.kirtanya.reel.core.stream.CachePolicy.evict(
            listOf(e("a", 3 * gb, used = 5, stopped = 100)),
            budget = 1 * gb, free = gb / 2, hardFloor = 1 * gb, explicit = false,
        )
        assertEquals(listOf("a"), out)
    }

    @Test fun claimedNeverEvictedEvenOnClear() {
        val out = com.kirtanya.reel.core.stream.CachePolicy.evict(
            listOf(e("playing", 9 * gb, used = 1, claimed = true), e("idle", gb, used = 2, stopped = 5)),
            budget = 0, free = 0, hardFloor = gb, explicit = true,
        )
        assertEquals(listOf("idle"), out)
    }
}
