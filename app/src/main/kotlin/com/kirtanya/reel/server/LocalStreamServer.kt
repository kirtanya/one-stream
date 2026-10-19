package com.kirtanya.reel.server

import android.util.Log
import com.kirtanya.reel.core.stream.ByteRange
import com.kirtanya.reel.engine.StreamInfo
import com.kirtanya.reel.engine.TorrentEngine
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Serves prepared streams on http://127.0.0.1:{port}/s/{infoHash}/{fileIndex} with Range
 * support, so ExoPlayer (or an external player) can play a torrent while it downloads.
 */
class LocalStreamServer(private val engine: TorrentEngine) : NanoHTTPD("127.0.0.1", 0) {

    fun ensureStarted() {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
    }

    fun urlFor(info: StreamInfo): String {
        ensureStarted()
        return "http://127.0.0.1:$listeningPort/s/${info.infoHash}/${info.file.index}"
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 3 || parts[0] != "s") return notFound()
        val info = engine.streamInfo(parts[1])?.takeIf { it.file.index == parts[2].toIntOrNull() }
            ?: return notFound()

        val size = info.file.size
        val rangeHeader = session.headers["range"]
        val range = ByteRange.parse(rangeHeader, size)
            ?: return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                .apply { addHeader("Content-Range", "bytes */$size") }

        val body: InputStream = if (session.method == Method.HEAD) ByteArrayInputStream(ByteArray(0)) else PieceInputStream(engine, info, range)
        val status = if (rangeHeader == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, info.mimeType, body, range.length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (rangeHeader != null) addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$size")
        }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
}

/**
 * Reads one byte range of a torrent file, blocking on each piece until libtorrent has it.
 * Every instance is a "reader" the engine plans deadlines for.
 */
private class PieceInputStream(
    private val engine: TorrentEngine,
    private val info: StreamInfo,
    range: ByteRange,
) : InputStream() {
    private val hash = info.infoHash
    private val span = info.span
    private var pos = range.start
    private val end = range.endInclusive
    private val readerId = engine.openReader(hash, pos)
    private var file: RandomAccessFile? = null
    /** Exclusive file position up to which data is known to be on disk. */
    private var readableUntil = -1L
    @Volatile private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("closed")
        if (pos > end) return -1
        if (len == 0) return 0
        if (pos >= readableUntil) waitForPieceAt(pos)
        val raf = file ?: openFile().also { file = it }
        val count = minOf(len.toLong(), readableUntil - pos, end - pos + 1).toInt()
        raf.seek(pos)
        val n = raf.read(b, off, count)
        if (n < 0) throw IOException("unexpected end of file")
        pos += n
        return n
    }

    private fun waitForPieceAt(filePos: Long) {
        val piece = span.pieceOf(filePos)
        engine.readerAt(hash, readerId, filePos)
        while (!engine.awaitPieceBlocking(hash, piece, 2_000)) {
            if (closed || !engine.isStreaming(hash)) throw IOException("stream stopped")
        }
        val pieceEndInTorrent = (piece + 1).toLong() * span.pieceLength
        readableUntil = minOf(pieceEndInTorrent - span.offset, span.size)
    }

    private fun openFile(): RandomAccessFile {
        repeat(50) {
            if (info.path.isFile) return RandomAccessFile(info.path, "r")
            Thread.sleep(100)
        }
        throw IOException("file not created: ${info.path}")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { file?.close() }
        engine.closeReader(hash, readerId)
        Log.d("ReelServer", "reader $readerId closed at $pos")
    }
}
