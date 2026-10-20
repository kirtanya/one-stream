package com.kirtanya.reel.core.stream

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Read-only view of which pieces a torrent has. */
interface PieceSet {
    val numPieces: Int
    fun has(p: Int): Boolean
    /** First missing piece in [from, toInclusive], or -1. */
    fun firstMissing(from: Int, toInclusive: Int): Int
    /** Last missing piece in [from, toInclusive], or -1. */
    fun lastMissing(from: Int, toInclusive: Int): Int
    /** Last piece q ≥ from such that from..q are all present (≤ limit); from - 1 if [from] is missing. */
    fun runEnd(from: Int, limitInclusive: Int): Int
}

/** Lock-free bitset of verified pieces, written by the alert thread and read by reader threads. */
class AtomicPieceSet(override val numPieces: Int) : PieceSet {
    private val words = AtomicLongArray((numPieces + 63) / 64)

    /** Sets [p]; returns true if it was newly set. */
    fun set(p: Int): Boolean {
        if (p !in 0 until numPieces) return false
        val w = p ushr 6
        val bit = 1L shl (p and 63)
        while (true) {
            val old = words.get(w)
            if (old and bit != 0L) return false
            if (words.compareAndSet(w, old, old or bit)) return true
        }
    }

    fun seed(has: (Int) -> Boolean) { for (p in 0 until numPieces) if (has(p)) set(p) }

    fun clearAll() { for (i in 0 until words.length()) words.set(i, 0) }

    fun count(): Int = (0 until words.length()).sumOf { java.lang.Long.bitCount(words.get(it)) }

    override fun has(p: Int): Boolean = p in 0 until numPieces && (words.get(p ushr 6) and (1L shl (p and 63))) != 0L

    override fun firstMissing(from: Int, toInclusive: Int): Int {
        for (p in from.coerceAtLeast(0)..toInclusive.coerceAtMost(numPieces - 1)) if (!has(p)) return p
        return -1
    }

    override fun lastMissing(from: Int, toInclusive: Int): Int {
        for (p in toInclusive.coerceAtMost(numPieces - 1) downTo from.coerceAtLeast(0)) if (!has(p)) return p
        return -1
    }

    override fun runEnd(from: Int, limitInclusive: Int): Int {
        var q = from - 1
        while (q + 1 <= limitInclusive && q + 1 < numPieces && has(q + 1)) q++
        return q
    }
}

/**
 * One per torrent: which pieces readers may read, and a place to wait for them.
 *
 * The alert thread sets a piece's bit and then signals under the lock; waiters re-check under
 * the same lock, so a piece finishing between "check" and "wait" can't be missed. Waits are in
 * short slices so a reader can notice it was stopped, superseded or that its client left, and
 * they're interruptible, so ExoPlayer's seek-cancel ends a blocked read at once.
 *
 * With the mmap disk backend a piece can be reported verified before all its blocks are written
 * ([needsFlush]); such a piece becomes readable once the engine confirms the flush
 * ([markFlushed]) or after a short grace period.
 */
class PieceGate(
    numPieces: Int,
    val needsFlush: Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    val pieces = AtomicPieceSet(numPieces)
    private val finishedAt = AtomicLongArray(numPieces)
    private val flushed = AtomicPieceSet(numPieces)
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    @Volatile private var closedReason: IOException? = null

    val numPieces get() = pieces.numPieces

    /** Alert thread: piece [p] passed its hash check. */
    fun markVerified(p: Int) {
        if (p !in 0 until numPieces) return
        if (pieces.set(p)) finishedAt.set(p, nanoTime())
        signalAll()
    }

    /** Seeding from libtorrent's bitfield (already on disk: no flush wait). */
    fun seedVerified(p: Int) {
        if (p !in 0 until numPieces) return
        if (pieces.set(p)) flushed.set(p)
    }

    /** Engine: piece [p]'s blocks are all on disk. */
    fun markFlushed(p: Int) {
        if (p !in 0 until numPieces) return
        flushed.set(p)
        signalAll()
    }

    fun flushPending(p: Int, graceNanos: Long = FLUSH_GRACE_NANOS): Boolean =
        needsFlush && pieces.has(p) && !flushed.has(p) && nanoTime() - finishedAt.get(p) < graceNanos

    fun isReadable(p: Int, graceNanos: Long = FLUSH_GRACE_NANOS): Boolean =
        pieces.has(p) && !flushPending(p, graceNanos)

    /** Last q ≥ [from] with from..q all readable (≤ limit); from - 1 when [from] isn't. */
    fun readableRunEnd(from: Int, limitInclusive: Int): Int {
        var q = from - 1
        while (q + 1 <= limitInclusive && q + 1 < numPieces && isReadable(q + 1)) q++
        return q
    }

    /**
     * Blocks until piece [p] is readable and [ready] holds. Every [sliceMs] it runs [onSlice],
     * which throws to abort (stream stopped, client gone). Throws [InterruptedException] when the
     * thread is interrupted, and the close reason when the gate is closed.
     */
    @Throws(InterruptedException::class)
    fun await(p: Int, sliceMs: Long = 250, ready: () -> Boolean = { true }, onSlice: () -> Unit = {}) {
        while (true) {
            lock.withLock {
                closedReason?.let { throw it }
                if (ready() && isReadable(p)) return
                cond.await(sliceMs, TimeUnit.MILLISECONDS)
                closedReason?.let { throw it }
                if (ready() && isReadable(p)) return
            }
            onSlice()
        }
    }

    /** Only when the torrent is removed: every waiter fails with [reason]. */
    fun close(reason: IOException) {
        closedReason = reason
        signalAll()
    }

    val isClosed get() = closedReason != null

    fun signalAll() = lock.withLock { cond.signalAll() }

    companion object {
        const val FLUSH_GRACE_NANOS = 2_000_000_000L
    }
}
