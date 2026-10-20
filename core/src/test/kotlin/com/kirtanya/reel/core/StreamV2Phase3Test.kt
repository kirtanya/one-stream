package com.kirtanya.reel.core

import com.kirtanya.reel.core.stream.AppliedState
import com.kirtanya.reel.core.stream.AtomicPieceSet
import com.kirtanya.reel.core.stream.ContainerKind
import com.kirtanya.reel.core.stream.ContainerProbe
import com.kirtanya.reel.core.stream.DiffContext
import com.kirtanya.reel.core.stream.DiskBackend
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.KiB
import com.kirtanya.reel.core.stream.MediaRate
import com.kirtanya.reel.core.stream.MiB
import com.kirtanya.reel.core.stream.PlanDiff
import com.kirtanya.reel.core.stream.PlanInput
import com.kirtanya.reel.core.stream.PlanMerge
import com.kirtanya.reel.core.stream.PlanOp
import com.kirtanya.reel.core.stream.PlaybackView
import com.kirtanya.reel.core.stream.ProbeStep
import com.kirtanya.reel.core.stream.RateSource
import com.kirtanya.reel.core.stream.ReaderKind
import com.kirtanya.reel.core.stream.ReaderView
import com.kirtanya.reel.core.stream.SchedulerProfile
import com.kirtanya.reel.core.stream.StartupPlanner
import com.kirtanya.reel.core.stream.StorageAllowance
import com.kirtanya.reel.core.stream.StreamPhase
import com.kirtanya.reel.core.stream.ZoneScheduler
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PHONE = SchedulerProfile.of(FormFactor.PHONE, DiskBackend.MMAP)

private fun input(
    span: FileSpan,
    have: AtomicPieceSet = AtomicPieceSet(span.numPieces),
    readers: List<ReaderView> = emptyList(),
    playback: PlaybackView? = null,
    phase: StreamPhase = StreamPhase.PLAYING,
    rate: Long = 1 * MiB,
    download: Boolean = false,
    storage: StorageAllowance = StorageAllowance(),
    startupDone: Boolean = true,
    startup: com.kirtanya.reel.core.stream.StartupSet? = null,
) = PlanInput(span, have, PHONE, phase, download, readers, null, playback, MediaRate(rate, RateSource.PLAYER_DURATION), startup, startupDone, storage) { 4 }

private fun inApp(pos: Long, blocked: Int = -1, id: Long = 1, openedAt: Long = 1) = ReaderView(id, ReaderKind.IN_APP, pos, openedAt, openedAt, blocked)
private fun playing(posMs: Long, bufMs: Long, dur: Long = 7_200_000) = PlaybackView(posMs, bufMs, dur, playing = true, playWhenReady = true, fresh = true)

class ZoneSchedulerTest {
    private val span = FileSpan(0, 1000L * MiB, MiB.toInt(), 1000)

    @Test fun blockedPieceIsDueNow() {
        val plan = ZoneScheduler.plan(input(span, readers = listOf(inApp(100 * MiB, blocked = 100)), playback = playing(0, 0)))
        assertEquals(0, plan.deadlines[100])
        assertEquals(7, plan.prio[100].toInt())
    }

    @Test fun deepBufferMeansOnlyTheSentinelIsDue() {
        // Reader 50 s ahead of the playhead at 1 MiB/s: nothing in the urgent zone is inside the horizon.
        val plan = ZoneScheduler.plan(input(span, readers = listOf(inApp(500 * MiB)), playback = playing(450_000, 500_000)))
        assertEquals(setOf(999), plan.deadlines.keys)
        assertEquals(999, plan.sentinel)
        assertEquals(6, plan.prio[500].toInt())
    }

    @Test fun shallowBufferGetsNearDeadlines() {
        val plan = ZoneScheduler.plan(input(span, readers = listOf(inApp(500 * MiB)), playback = playing(495_000, 500_000)))
        assertTrue(500 in plan.deadlines)
        val ds = plan.deadlines.filterKeys { it != plan.sentinel }.toSortedMap().values.toList()
        assertEquals(ds.sorted(), ds) // non-decreasing in file order
    }

    @Test fun pausedFloorPushesUrgentOutOfTheHorizon() {
        val paused = PlaybackView(495_000, 500_000, 7_200_000, playing = false, playWhenReady = true, fresh = true)
        val plan = ZoneScheduler.plan(input(span, readers = listOf(inApp(500 * MiB)), playback = paused))
        assertEquals(setOf(999), plan.deadlines.keys)
    }

    @Test fun sentinelIsTheLastMissingAndAbsentWhenComplete() {
        val have = AtomicPieceSet(1000).apply { for (p in 0 until 1000) if (p != 700) set(p) }
        val plan = ZoneScheduler.plan(input(span, have, readers = listOf(inApp(500 * MiB)), playback = playing(450_000, 500_000)))
        assertEquals(700, plan.sentinel)
        val all = AtomicPieceSet(1000).apply { for (p in 0 until 1000) set(p) }
        assertNull(ZoneScheduler.plan(input(span, all, readers = listOf(inApp(500 * MiB)), playback = playing(450_000, 500_000))).sentinel)
    }

    @Test fun sequentialRangeStartsAtTheFirstMissingPiece() {
        val have = AtomicPieceSet(1000).apply { for (p in 500..510) set(p) }
        val plan = ZoneScheduler.plan(input(span, have, readers = listOf(inApp(500 * MiB)), playback = playing(450_000, 500_000)))
        assertEquals(511..999, plan.seq)
    }

    @Test fun downloadNeverDropsBelowNormal() {
        val plan = ZoneScheduler.plan(input(span, download = true, readers = listOf(inApp(500 * MiB)), playback = playing(450_000, 500_000)))
        assertTrue(plan.prio.all { it >= 4 })
    }

    @Test fun meteredWindowBoundsTheReadAhead() {
        val plan = ZoneScheduler.plan(input(span, readers = listOf(inApp(500 * MiB)), playback = playing(450_000, 500_000),
            storage = StorageAllowance(fullFile = false, aheadCapBytes = 100 * MiB, metered = true)))
        assertEquals(600, plan.aheadEndPiece)
        assertEquals(0, plan.prio[700].toInt())
        assertEquals(600, plan.seq!!.last)
    }

    @Test fun startupFetchesHeaderThenIndex() {
        val st = StartupPlanner.initial(span, "mkv", 0, null, MediaRate(MiB, RateSource.GUESS), PHONE)
        val plan = ZoneScheduler.plan(input(span, phase = StreamPhase.STARTUP, startupDone = false, startup = st))
        assertEquals(0, plan.deadlines[0])
        assertTrue(plan.deadlines[1]!! < plan.deadlines[996]!!.coerceAtLeast(1) || plan.deadlines[996] == 25)
        assertTrue(996 in plan.deadlines && 999 in plan.deadlines)
        assertNull(plan.seq)
    }

    @Test fun httpReaderBecomesPrimaryWhenInAppIsStale() {
        val stale = PlaybackView(0, 0, 7_200_000, playing = false, playWhenReady = true, fresh = false)
        val http = ReaderView(2, ReaderKind.HTTP, 300 * MiB, openedAt = 5, lastProgressAt = 9)
        val plan = ZoneScheduler.plan(input(span, readers = listOf(inApp(100 * MiB), http), playback = stale))
        assertEquals(2, plan.primaryId)
    }

    @Test fun urgentZoneIsBoundedForBigPieces() {
        val big = FileSpan(0, 60L * 1024 * MiB, 16 * MiB.toInt(), 3840)
        val plan = ZoneScheduler.plan(input(big, readers = listOf(inApp(0)), playback = playing(0, 0), rate = 10 * MiB))
        assertTrue(plan.deadlines.size <= PHONE.urgentMaxPieces + 1)
    }
}

class PlanDiffTest {
    private val span = FileSpan(0, 200L * MiB, MiB.toInt(), 200)
    private val have = AtomicPieceSet(200)
    private fun ctx(now: Long, forceFull: Boolean = false, forceClear: Boolean = false) =
        DiffContext(now, forceFull, forceClear, have, PHONE, 200, farSeekBytes = 64 * MiB)
    private fun merged(reader: Long, bufMs: Long = 2_000) = PlanMerge.merge(
        ZoneScheduler.plan(input(span, have, readers = listOf(inApp(reader)), playback = playing(reader / MiB * 1000 - bufMs, reader / MiB * 1000))),
        span, emptyList(), { 4 }, have, 200, streamMode = true, prevGuard = null, pieceLength = MiB.toInt(), rate = MediaRate(MiB, RateSource.PLAYER_DURATION), nowMs = 0)

    @Test fun firstApplyIsFullAndRepeatIsEmpty() {
        val (ops1, state) = PlanDiff.diff(null, merged(10 * MiB), ctx(0))
        assertTrue(ops1.any { it is PlanOp.SetAllPriorities })
        val (ops2, _) = PlanDiff.diff(state, merged(10 * MiB), ctx(100))
        assertTrue(ops2.isEmpty(), "unexpected ops $ops2")
    }

    @Test fun addsComeBeforeRemovalsOnANormalStep() {
        val (_, s1) = PlanDiff.diff(null, merged(10 * MiB), ctx(0))
        have.set(10); have.set(11)
        val (ops, _) = PlanDiff.diff(s1, merged(12 * MiB), ctx(1_000))
        val firstReset = ops.indexOfFirst { it is PlanOp.ResetDeadline }
        val lastSet = ops.indexOfLast { it is PlanOp.SetDeadline }
        assertTrue(firstReset == -1 || lastSet < firstReset)
        assertFalse(ops.any { it is PlanOp.ClearDeadlines })
        // Had pieces never appear.
        assertFalse(ops.any { (it as? PlanOp.SetDeadline)?.piece in setOf(10, 11) || (it as? PlanOp.ResetDeadline)?.piece in setOf(10, 11) })
    }

    @Test fun farSeekClearsExactlyOnce() {
        val (_, s1) = PlanDiff.diff(null, merged(10 * MiB), ctx(0))
        val (ops, _) = PlanDiff.diff(s1, merged(150 * MiB), ctx(1_000))
        assertEquals(1, ops.count { it is PlanOp.ClearDeadlines })
        assertEquals(PlanOp.ClearDeadlines, ops.first())
    }
}

class PlanMergeGuardTest {
    @Test fun completeClaimedStreamKeepsOneMissingPieceWanted() {
        val have = AtomicPieceSet(10).apply { for (p in 0..4) set(p) }
        val file = FileSpan(0, 5L * MiB, MiB.toInt(), 10)
        val plan = ZoneScheduler.plan(PlanInput(file, have, PHONE, StreamPhase.PLAYING, false, listOf(inApp(4 * MiB)), null, playing(0, 0), MediaRate(MiB, RateSource.PLAYER_DURATION), null, true) { 0 })
        val m = PlanMerge.merge(plan, file, emptyList(), { if (it < 5) 4 else 0 }, have, 10, streamMode = true, prevGuard = null,
            pieceLength = MiB.toInt(), rate = MediaRate(MiB, RateSource.PLAYER_DURATION), nowMs = 0)
        assertNotNull(m.guard)
        assertEquals(5, m.guard!!.piece)
        assertTrue(m.vector[5] >= 1)
    }
}

class ContainerProbeTest {
    private fun box(type: String, payload: Int): ByteArray = ByteArrayOutputStream().apply {
        val size = 8 + payload
        write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        write(type.toByteArray()); write(ByteArray(payload))
    }.toByteArray()

    private fun probe(bytes: ByteArray, ext: String, declaredSize: Long = bytes.size.toLong()): ProbeStep {
        val p = ContainerProbe(declaredSize, ext)
        var step = p.step { pos, len -> if (pos < bytes.size) bytes.copyOfRange(pos.toInt(), minOf(bytes.size, (pos + len).toInt())) else null }
        repeat(3) { if (step is ProbeStep.Need) step = p.step { pos, len -> if (pos < bytes.size) bytes.copyOfRange(pos.toInt(), minOf(bytes.size, (pos + len).toInt())) else null } }
        return step
    }

    @Test fun faststartMp4NeedsNoTail() {
        val file = box("ftyp", 16) + box("moov", 1000) + box("mdat", 5000)
        val l = (probe(file, "mp4") as ProbeStep.Done).layout
        assertEquals(ContainerKind.MP4, l.kind)
        assertFalse(l.needTail)
    }

    @Test fun moovAtTheEnd() {
        val file = box("ftyp", 16) + box("mdat", 5000) + box("moov", 1000)
        val l = (probe(file, "mp4") as ProbeStep.Done).layout
        assertTrue(l.needTail)
        assertEquals(24L + 5008, l.index!!.first)
    }

    @Test fun fragmentedMp4() {
        val file = box("ftyp", 16) + box("moov", 100) + box("moof", 50) + box("mdat", 500)
        assertEquals(ContainerKind.FMP4, (probe(file, "mp4") as ProbeStep.Done).layout.kind)
    }

    @Test fun mkvSeekHeadPointsAtCues() {
        // EBML header, Segment (unknown size), SeekHead → Seek(Cues @ 1_000_000), then a Cluster.
        fun vint(v: Long, len: Int): ByteArray = ByteArray(len) { i -> ((v ushr (8 * (len - 1 - i))) and 0xFF).toByte() }.also { it[0] = (it[0].toInt() or (0x80 ushr (len - 1))).toByte() }
        val seek = byteArrayOf(0x53, 0xAB.toByte()) + vint(4, 1) + byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B) +
            byteArrayOf(0x53, 0xAC.toByte()) + vint(4, 1) + byteArrayOf(0x00, 0x0F, 0x42, 0x40)
        val seekEl = byteArrayOf(0x4D, 0xBB.toByte()) + vint(seek.size.toLong(), 1) + seek
        val seekHead = byteArrayOf(0x11, 0x4D, 0x9B.toByte(), 0x74) + vint(seekEl.size.toLong(), 1) + seekEl
        val cluster = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75) + vint(10, 1) + ByteArray(10)
        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + vint(4, 1) + ByteArray(4)
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67) + byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val file = ebml + segment + seekHead + cluster
        val segmentStart = (ebml.size + segment.size).toLong()
        val l = (probe(file, "mkv", declaredSize = 50L * MiB) as ProbeStep.Done).layout
        assertEquals(ContainerKind.MKV, l.kind)
        assertEquals(segmentStart + 1_000_000, l.index!!.first)
    }

    @Test fun mkvWithoutSeekHeadKeepsTheTail() {
        val file = byteArrayOf(0, 0, 0, 0)
        val l = (probe(file, "mkv", declaredSize = 50L * MiB) as ProbeStep.Done).layout
        assertTrue(l.needTail)
    }

    @Test fun startupResumeGuessOnlyWithAKnownRate() {
        val span = FileSpan(0, 4L * 1024 * MiB, 4 * MiB.toInt(), 1024)
        assertNull(StartupPlanner.initial(span, "mkv", 60_000, null, MediaRate(MiB, RateSource.GUESS), PHONE).resumeGuess)
        val g = StartupPlanner.initial(span, "mkv", 3_600_000, 7_200_000, MediaRate(600 * KiB, RateSource.HINT_DURATION), PHONE).resumeGuess
        assertNotNull(g)
        assertTrue(2L * 1024 * MiB in g)
    }

    @Test fun mediaRateOrder() {
        assertEquals(RateSource.PLAYER_DURATION, MediaRate.of(1000, 10, 20).source)
        assertEquals(RateSource.HINT_DURATION, MediaRate.of(1000, null, 20).source)
        assertEquals(256 * KiB, MediaRate.of(1000, null, null).bytesPerSec)
    }
}
