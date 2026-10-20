package com.kirtanya.reel.core

import com.kirtanya.reel.core.stream.AtomicPieceSet
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.PieceGate
import com.kirtanya.reel.core.stream.PlayerBuffers
import com.kirtanya.reel.core.stream.ReaderKind
import com.kirtanya.reel.core.stream.ReaderListener
import com.kirtanya.reel.core.stream.StreamGoneException
import com.kirtanya.reel.core.stream.StreamReaderCore
import com.kirtanya.reel.core.stream.TorrentStreamRef
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PieceSetTest {
    @Test fun runsAndMissing() {
        val s = AtomicPieceSet(130)
        listOf(0, 1, 2, 5, 64, 65, 129).forEach { s.set(it) }
        assertEquals(2, s.runEnd(0, 100))
        assertEquals(2, s.runEnd(0, 2))
        assertEquals(2, s.runEnd(3, 100))      // 3 missing → from - 1
        assertEquals(3, s.firstMissing(0, 129))
        assertEquals(128, s.lastMissing(0, 129))
        assertEquals(-1, s.firstMissing(64, 65))
        assertEquals(7, s.count())
        assertFalse(s.set(5))
    }
}

class PieceGateTest {
    @Test fun signalBeforeAwaitIsNeverLost() {
        repeat(2_000) {
            val gate = PieceGate(4, needsFlush = false)
            val done = CountDownLatch(1)
            val t = thread { gate.await(1, sliceMs = 5_000); done.countDown() }
            gate.markVerified(1)
            assertTrue(done.await(2, TimeUnit.SECONDS), "waiter missed the signal")
            t.join()
        }
    }

    @Test fun interruptEndsTheWaitQuickly() {
        val gate = PieceGate(4, needsFlush = false)
        val caught = AtomicReference<Throwable?>()
        val t = thread { try { gate.await(0, sliceMs = 10_000) } catch (e: Throwable) { caught.set(e) } }
        Thread.sleep(50)
        val t0 = System.nanoTime()
        t.interrupt(); t.join(1_000)
        assertTrue(caught.get() is InterruptedException)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
    }

    @Test fun closeWakesWaitersWithItsReason() {
        val gate = PieceGate(4, needsFlush = false)
        val caught = AtomicReference<Throwable?>()
        val t = thread { try { gate.await(0, sliceMs = 10_000) } catch (e: Throwable) { caught.set(e) } }
        Thread.sleep(50)
        gate.close(StreamGoneException("removed"))
        t.join(1_000)
        assertEquals("removed", caught.get()?.message)
    }

    @Test fun onSliceAbortPropagates() {
        val gate = PieceGate(4, needsFlush = false)
        assertFailsWith<StreamGoneException> { gate.await(0, sliceMs = 5) { throw StreamGoneException("client left") } }
    }

    @Test fun flushPendingUntilFlushedOrGrace() {
        var now = 0L
        val gate = PieceGate(4, needsFlush = true) { now }
        gate.markVerified(1)
        assertTrue(gate.flushPending(1))
        assertFalse(gate.isReadable(1))
        now += PieceGate.FLUSH_GRACE_NANOS + 1
        assertTrue(gate.isReadable(1))
        gate.markVerified(2)              // finished at the new 'now'
        assertFalse(gate.isReadable(2))
        gate.markFlushed(2)
        assertTrue(gate.isReadable(2))
    }

    @Test fun readableRunStopsAtAFlushPendingPiece() {
        var now = 0L
        val gate = PieceGate(4, needsFlush = true) { now }
        gate.seedVerified(1)              // seeded = on disk already
        gate.markVerified(2)              // just verified, not flushed
        assertEquals(1, gate.readableRunEnd(1, 3))
    }

    @Test fun readyPredicateBlocksUntilItFlips() {
        val gate = PieceGate(4, needsFlush = false)
        gate.markVerified(0)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = CountDownLatch(1)
        val t = thread { gate.await(0, sliceMs = 5_000, ready = { ready.get() }); done.countDown() }
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        ready.set(true)
        gate.signalAll()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        t.join()
    }
}

class StreamReaderCoreTest {
    private val pl = 1024
    private fun fixture(pieces: Int): Pair<File, FileSpan> {
        val f = File.createTempFile("reel", ".bin").apply { deleteOnExit() }
        f.writeBytes(ByteArray(pieces * pl) { (it % 251).toByte() })
        return f to FileSpan(0, f.length(), pl, pieces)
    }

    private fun reader(f: File, span: FileSpan, gate: PieceGate, alive: () -> Unit = {}, listener: ReaderListener = object : ReaderListener {}) =
        StreamReaderCore(1, ReaderKind.IN_APP, span, gate, { RandomAccessFile(f, "r") }, { true }, alive, null, listener)

    @Test fun smallReadsComeFromTheBufferAndMatchTheFile() {
        // Parsers read a few bytes at a time, jumping around: every byte must still be the file's.
        val (f, span) = fixture(200)
        val gate = PieceGate(200, needsFlush = false).apply { for (p in 0 until 200) seedVerified(p) }
        val r = reader(f, span, gate)
        val rnd = java.util.Random(7)
        val bytes = f.readBytes()
        repeat(5_000) {
            val pos = if (rnd.nextInt(4) == 0) rnd.nextInt(bytes.size).toLong() else (r.position).coerceAtMost(bytes.size - 1L)
            val len = 1 + rnd.nextInt(if (rnd.nextInt(10) == 0) 50_000 else 16)
            val buf = ByteArray(len)
            val n = r.read(pos, buf, 0, len)
            assertTrue(n > 0)
            for (i in 0 until n) assertEquals(bytes[(pos + i).toInt()], buf[i], "byte ${pos + i}")
        }
        r.close()
    }

    @Test fun readsExtendAcrossReadablePiecesButNotPastThem() {
        val (f, span) = fixture(4)
        val gate = PieceGate(4, needsFlush = false).apply { seedVerified(0); seedVerified(1) }
        val r = reader(f, span, gate)
        val buf = ByteArray(4 * pl)
        val n = r.read(0, buf, 0, buf.size)
        assertEquals(2 * pl, n)            // stops at the end of piece 1
        assertEquals((pl % 251).toByte(), buf[pl])
        r.close()
    }

    @Test fun neverReadsPastEof() {
        val (f, span) = fixture(2)
        val gate = PieceGate(2, needsFlush = false).apply { seedVerified(0); seedVerified(1) }
        val r = reader(f, span, gate)
        assertEquals(-1, r.read(span.size, ByteArray(10), 0, 10))
        r.close()
    }

    @Test fun blocksThenReadsWhenThePieceArrives() {
        val (f, span) = fixture(3)
        val gate = PieceGate(3, needsFlush = false)
        val blocked = CountDownLatch(1)
        val r = reader(f, span, gate, listener = object : ReaderListener { override fun onBlocked(piece: Int) = blocked.countDown() })
        val result = AtomicReference<Int>()
        val t = thread { result.set(r.read(pl.toLong(), ByteArray(100), 0, 100)) }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        gate.markVerified(1)
        t.join(2_000)
        assertEquals(100, result.get())
        r.close()
    }

    @Test fun interruptedReadThrowsInterruptedIo() {
        val (f, span) = fixture(2)
        val gate = PieceGate(2, needsFlush = false)
        val r = reader(f, span, gate)
        val caught = AtomicReference<Throwable?>()
        val t = thread { try { r.read(0, ByteArray(10), 0, 10) } catch (e: Throwable) { caught.set(e) } }
        Thread.sleep(50); t.interrupt(); t.join(1_000)
        assertTrue(caught.get() is InterruptedIOException)
    }

    @Test fun markDeadDuringAWaitFailsAsGone() {
        val (f, span) = fixture(2)
        val gate = PieceGate(2, needsFlush = false)
        val r = reader(f, span, gate)
        val caught = AtomicReference<Throwable?>()
        val t = thread { try { r.read(0, ByteArray(10), 0, 10) } catch (e: Throwable) { caught.set(e) } }
        Thread.sleep(50)
        r.markDead(StreamGoneException("superseded")); gate.signalAll()
        t.join(1_000)
        assertTrue(caught.get() is StreamGoneException)
    }

    @Test fun supersededStreamFailsAtItsNextRead() {
        val (f, span) = fixture(2)
        val gate = PieceGate(2, needsFlush = false).apply { seedVerified(0) }
        var gone: StreamGoneException? = null
        val r = reader(f, span, gate, alive = { gone?.let { throw it } })
        assertEquals(10, r.read(0, ByteArray(10), 0, 10))
        gone = StreamGoneException("superseded")
        assertFailsWith<StreamGoneException> { r.read(10, ByteArray(10), 0, 10) }
    }

    @Test fun longBlockFiresWhileBlocked() {
        val (f, span) = fixture(2)
        val gate = PieceGate(2, needsFlush = false)
        var clock = 0L
        val longBlocks = CountDownLatch(1)
        val r = StreamReaderCore(1, ReaderKind.IN_APP, span, gate, { RandomAccessFile(f, "r") }, { true }, {}, { clock += 3_000_000_000L },
            object : ReaderListener { override fun longBlock(piece: Int) = longBlocks.countDown() }, clock = { clock })
        val t = thread { runCatching { r.read(0, ByteArray(10), 0, 10) } }
        assertTrue(longBlocks.await(2, TimeUnit.SECONDS))
        t.interrupt(); t.join(1_000)
    }
}

class TorrentStreamRefTest {
    private val key = "ab".repeat(20)
    @Test fun roundTrip() {
        val ref = TorrentStreamRef(key, 3, 42, "Show S01E02 [1080p].mkv")
        assertEquals(ref, TorrentStreamRef.parse(ref.format()))
        assertTrue(ref.format().endsWith(".mkv"))
    }
    @Test fun rejectsJunk() {
        assertNull(TorrentStreamRef.parse("http://x"))
        assertNull(TorrentStreamRef.parse("reel-torrent://nothex/1/2/a.mkv"))
        assertNull(TorrentStreamRef.parse("reel-torrent://$key/x/2/a.mkv"))
    }
}

class BackBufferTest {
    private val mb = 1_048_576L
    @Test fun phone() {
        assertEquals(30_000, PlayerBuffers.backBufferMs(FormFactor.PHONE, 1 * mb))
        assertEquals(8_000, PlayerBuffers.backBufferMs(FormFactor.PHONE, 4 * mb))
        assertEquals(5_000, PlayerBuffers.backBufferMs(FormFactor.PHONE, 12 * mb))
    }
    @Test fun tv() {
        assertEquals(10_000, PlayerBuffers.backBufferMs(FormFactor.TV, 1 * mb))
        assertEquals(3_000, PlayerBuffers.backBufferMs(FormFactor.TV, 4 * mb))
        assertEquals(3_000, PlayerBuffers.backBufferMs(FormFactor.TV, 12 * mb))
    }
    @Test fun rate() {
        assertEquals(1000L, PlayerBuffers.rate(1_000_000, 1_000_000, null))
        assertEquals(3_125_000L, PlayerBuffers.rate(null, null, "2160p"))
    }
}
