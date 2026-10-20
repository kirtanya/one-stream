package com.kirtanya.reel.core.stream

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** Who is reading: the in-app player, an external player over HTTP, or the bench. */
enum class ReaderKind { IN_APP, HTTP, BENCH }

/** The stream was stopped, superseded or removed, or the client left: fail fast, never retry. */
class StreamGoneException(message: String) : IOException(message)

enum class StreamErrorKind { NO_METADATA, STORAGE_FULL, FILE_ERROR, TORRENT_ERROR, GONE }

/** A real error the user should see (disk full, file error…). */
class StreamErrorException(val kind: StreamErrorKind, message: String) : IOException(message)

/** Opens the file being streamed; called again when its path changes (after Keep). */
fun interface FileOpener {
    fun open(): RandomAccessFile
}

/** What a reader tells the engine. Every callback must be cheap (a post); no libtorrent calls. */
interface ReaderListener {
    fun onBlocked(piece: Int) {}
    fun onUnblocked(piece: Int, waitedMs: Long) {}
    fun onPieceCrossed(position: Long) {}
    fun needFlush(piece: Int) {}
    fun longBlock(piece: Int) {}
}

/**
 * The blocking read loop shared by the in-app DataSource, the HTTP server and the bench.
 *
 * A read never fails for being slow: it waits for the piece. It fails only when interrupted
 * (seek cancel), when [alive] throws (stopped, superseded, removed) or when [probe] sees the
 * client gone. The engine never closes the file under a reader; the owning thread does, in
 * [close]. RandomAccessFile is used because an interrupt can't close it (a FileChannel would).
 */
class StreamReaderCore(
    val id: Long,
    val kind: ReaderKind,
    val span: FileSpan,
    private val gate: PieceGate,
    private val opener: FileOpener,
    private val fileReady: () -> Boolean,
    private val alive: () -> Unit,
    private val probe: (() -> Unit)?,
    private val listener: ReaderListener,
    private val clock: () -> Long = System::nanoTime,
) : Closeable {
    @Volatile var position: Long = 0
    @Volatile var blockedPiece: Int = -1
    @Volatile var lastProgressNanos: Long = clock()
    @Volatile var bytesRead: Long = 0
    @Volatile var deadReason: IOException? = null
    val openedNanos: Long = clock()

    private var readableStart = 0L
    private var readableEnd = 0L
    private var file: RandomAccessFile? = null
    private var lastPiece = -1
    /** Where the file pointer is (skips a seek syscall on sequential reads), -1 unknown. */
    private var filePos = -1L
    /**
     * Small reads come from memory: ExoPlayer's MKV / MP4 parsers read headers and indexes a few
     * bytes at a time, and a seek + read syscall each took 26 s for one film's Cues on the TV.
     * Only verified (readable) bytes are buffered; they never change.
     */
    private val buffer = ByteArray(BUFFER_BYTES)
    private var bufStart = 0L
    private var bufEnd = 0L

    /** Engine side: makes this reader fail at its next check (the caller then signals the gate). */
    fun markDead(reason: IOException) { deadReason = reason }

    private fun check() {
        deadReason?.let { throw it }
        alive()
    }

    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        check()
        if (len == 0) return 0
        if (pos >= span.size) return -1
        if (pos !in readableStart until readableEnd) {
            val p = span.pieceOf(pos)
            if (!(fileReady() && gate.isReadable(p))) waitFor(p)
            val limit = span.pieceOf(minOf(span.size - 1, pos + READ_RUN_LIMIT))
            val last = gate.readableRunEnd(p, limit).coerceAtLeast(p)
            readableStart = span.byteStartOf(p)
            readableEnd = span.byteEndOf(last)
        }
        val raf = file ?: opener.open().also { file = it; filePos = -1 }
        val n = minOf(len.toLong(), readableEnd - pos).toInt()
        val r: Int
        if (pos >= bufStart && pos < bufEnd) {
            r = minOf(n.toLong(), bufEnd - pos).toInt()
            System.arraycopy(buffer, (pos - bufStart).toInt(), buf, off, r)
        } else if (n < BUFFER_BYTES / 2) {
            val want = minOf(BUFFER_BYTES.toLong(), readableEnd - pos).toInt()
            if (filePos != pos) raf.seek(pos)
            var got = 0
            while (got < want) {
                val k = raf.read(buffer, got, want - got)
                if (k < 0) break
                got += k
            }
            filePos = pos + got
            if (got <= 0) throw StreamErrorException(StreamErrorKind.FILE_ERROR, "The file is shorter than expected")
            bufStart = pos
            bufEnd = pos + got
            r = minOf(n, got)
            System.arraycopy(buffer, 0, buf, off, r)
        } else {
            if (filePos != pos) raf.seek(pos)
            r = raf.read(buf, off, n)
            filePos = if (r > 0) pos + r else -1
        }
        check()
        if (r < 0) throw StreamErrorException(StreamErrorKind.FILE_ERROR, "The file is shorter than expected")
        position = pos + r
        bytesRead += r
        lastProgressNanos = clock()
        val piece = span.pieceOf(position.coerceAtMost(span.size - 1))
        if (piece != lastPiece) {
            lastPiece = piece
            listener.onPieceCrossed(position)
        }
        return r
    }

    private fun waitFor(p: Int) {
        blockedPiece = p
        val t0 = clock()
        listener.onBlocked(p)
        if (gate.flushPending(p)) listener.needFlush(p)
        var lastLong = t0
        try {
            gate.await(p, SLICE_MS, ready = fileReady) {
                check()
                probe?.invoke()
                val now = clock()
                if (now - lastLong >= LONG_BLOCK_NANOS) {
                    lastLong = now
                    listener.longBlock(p)
                }
                if (gate.flushPending(p)) listener.needFlush(p)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } finally {
            blockedPiece = -1
        }
        listener.onUnblocked(p, (clock() - t0) / 1_000_000)
    }

    /** The path changed (Keep): drop the open file so the next read reopens it. */
    fun reopen() {
        runCatching { file?.close() }
        file = null
    }

    override fun close() {
        runCatching { file?.close() }
        file = null
    }

    companion object {
        const val SLICE_MS = 250L
        const val LONG_BLOCK_NANOS = 2_000_000_000L
        /** How far ahead one readable run may extend (one open file read can't run past this). */
        const val READ_RUN_LIMIT = 64 * MiB
        const val BUFFER_BYTES = 64 * 1024
    }
}
