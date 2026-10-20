package com.kirtanya.reel.server

import android.util.Log
import com.kirtanya.reel.core.stream.ByteRange
import com.kirtanya.reel.core.stream.ReaderKind
import com.kirtanya.reel.core.stream.StreamGoneException
import com.kirtanya.reel.core.stream.TorrentStreamRef
import com.kirtanya.reel.engine.StreamInfo
import com.kirtanya.reel.engine.StreamReader
import com.kirtanya.reel.engine.TorrentEngine
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * Serves prepared streams to **external** players (VLC, MX…) on
 * http://127.0.0.1:{port}/s/{key}/{fileIndex}/{token} with Range support. The in-app player
 * reads directly (TorrentDataSource), with no HTTP hop.
 *
 * Each connection's socket is bound to its reader, which probes it while waiting for a piece,
 * so a player that seeks or closes is noticed within about a second instead of keeping a stale
 * read-ahead window alive. Responses close the connection, so an aborted body ends at once.
 */
class LocalStreamServer(private val engine: TorrentEngine) : NanoHTTPD("127.0.0.1", 0) {

    fun ensureStarted() {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
    }

    /** Legacy entry point (ReelApp still builds one lazily but never uses it). */
    fun urlFor(info: StreamInfo): String {
        ensureStarted()
        return "http://127.0.0.1:$listeningPort/s/${info.infoHash}/${info.file.index}/${info.token}"
    }

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler =
        object : ClientHandler(inputStream, finalAccept) {
            override fun run() {
                CURRENT_SOCKET.set(finalAccept)
                try {
                    super.run()
                } finally {
                    CURRENT_SOCKET.remove()
                }
            }
        }

    override fun useGzipWhenAccepted(r: Response?): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 4 || parts[0] != "s") return notFound()
        val idx = parts[2].toIntOrNull() ?: return notFound()
        val token = parts[3].toLongOrNull() ?: return notFound()
        val info = engine.streamInfo(parts[1])?.takeIf { it.file.index == idx && it.token == token } ?: return notFound()

        val size = info.file.size
        val rangeHeader = session.headers["range"]
        val range = ByteRange.parse(rangeHeader, size)
            ?: return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                .apply { addHeader("Content-Range", "bytes */$size") }

        val body: InputStream = if (session.method == Method.HEAD) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            val reader = try {
                engine.openReader(TorrentStreamRef(info.infoHash, idx, token, info.file.name), range.start, ReaderKind.HTTP, CURRENT_SOCKET.get())
            } catch (e: StreamGoneException) {
                return notFound()
            }
            ReaderInputStream(reader, range.start, range.endInclusive)
        }
        val status = if (rangeHeader == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, info.mimeType, body, range.length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (rangeHeader != null) addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$size")
            closeConnection(true)
        }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found").apply { closeConnection(true) }

    companion object {
        private val CURRENT_SOCKET = ThreadLocal<Socket?>()
    }
}

/** An [InputStream] over one byte range of a [StreamReader]. */
private class ReaderInputStream(private val reader: StreamReader, start: Long, private val end: Long) : InputStream() {
    private var pos = start
    @Volatile private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("closed")
        if (pos > end) return -1
        if (len == 0) return 0
        val n = reader.read(pos, b, off, minOf(len.toLong(), end - pos + 1).toInt())
        if (n < 0) return -1
        pos += n
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        reader.close()
        Log.d("ReelServer", "reader ${reader.core.id} closed at $pos")
    }
}
