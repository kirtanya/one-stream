package com.kirtanya.reel.core.httpdl

import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.HomeStore
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One attempt to fetch (or continue) a direct download into a [HomeStore] (docs/HOME_DISK.md §25.3). Blocking: call off the main thread.
 * It decides nothing about retries or states: it returns what happened ([Outcome]) and the caller (the app's `HttpDownloader`) applies
 * [RetryPolicy]. Everything it needs from outside comes through [Listener], so tests drive it with a local server and a temp folder.
 */
class HttpFetcher(
    client: OkHttpClient,
    private val userAgent: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushBytes: Long = FLUSH_BYTES,
    private val flushMs: Long = FLUSH_MS,
) {
    // A debrid link can take a while to answer, and a stalled body must fail so it is retried; no overall call limit (a film is GBs).
    private val http = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    interface Listener {
        /** True once the user paused / deleted: the attempt stops at the next buffer. */
        val cancelled: Boolean

        /** The call is in flight: the owner may cancel it to unblock a read. */
        fun onCall(call: Call)

        /** The answer is usable (the guard passed): the real [total] and [validator]; [restarted] = the saved progress was dropped. */
        fun onStart(total: Long, validator: String?, restarted: Boolean)

        /** Bytes written so far (memory only, frequent). */
        fun onBytes(done: Long)

        /** [done] bytes are on the disk: a restart may resume here. */
        fun onFlushed(done: Long)
    }

    sealed interface Outcome {
        data class Done(val total: Long) : Outcome
        data class Retry(val kind: FailKind, val retryAfterSec: Long?, val message: String, val progressed: Boolean) : Outcome
        data class Failed(val message: String) : Outcome
        data class NotReady(val stat: String) : Outcome
        data object NotVideo : Outcome
        data class NoSpace(val total: Long) : Outcome
        data class DiskError(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    fun fetch(d: HttpDownload, store: HomeStore, place: DownloadPlace, freeBytes: () -> Long, l: Listener): Outcome {
        var start = d.doneBytes
        // The part file is gone (cleaned, another disk): the saved position means nothing.
        if (start > 0 && store.read(d.path, start - 1, ByteArray(1), 0, 1, part = true) < 1) start = 0
        for (pass in 0..1) {
            if (l.cancelled) return Outcome.Cancelled
            val response = try {
                http.newCall(request(d, start)).also(l::onCall).execute()
            } catch (e: IOException) {
                return if (l.cancelled) Outcome.Cancelled else Outcome.Retry(FailKind.NETWORK, null, "Waiting for the connection", false)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failed("The link is not valid")
            }
            response.use { r ->
                val validator = r.header("ETag") ?: r.header("Last-Modified")
                val facts = ResponseFacts(
                    r.code, r.body?.contentLength()?.takeIf { it >= 0 }, r.header("Content-Range"), validator,
                    RetryPolicy.parseRetryAfter(r.header("Retry-After")),
                )
                when (val step = HttpResume.plan(start, d.totalBytes, d.validator, facts)) {
                    is ResumeStep.Begin -> return stream(r, d, store, place, freeBytes, l, step)
                    is ResumeStep.AlreadyDone -> return finish(store, d, step.total, l)
                    is ResumeStep.Retry -> return Outcome.Retry(step.kind, step.retryAfterSec, step.message, false)
                    is ResumeStep.Fail -> return Outcome.Failed(step.message)
                    is ResumeStep.Refetch -> {
                        if (pass == 1) return Outcome.Failed("The server did not answer a plain request")
                        start = 0
                    }
                }
            }
        }
        return Outcome.Failed("The server did not answer")
    }

    private fun request(d: HttpDownload, from: Long): Request {
        val b = Request.Builder().url(d.url)
        d.headers.forEach { (k, v) -> b.header(k, v) }
        if (d.headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", userAgent)
        // Never let OkHttp ask for gzip: it would hide Content-Length (and a ranged gzip body is nonsense for a video).
        if (d.headers.keys.none { it.equals("Accept-Encoding", true) }) b.header("Accept-Encoding", "identity")
        if (from > 0) {
            b.header("Range", "bytes=$from-")
            // Weak ETags are not allowed in If-Range.
            d.validator?.takeIf { !it.startsWith("W/") }?.let { b.header("If-Range", it) }
        }
        return b.build()
    }

    private fun finish(store: HomeStore, d: HttpDownload, total: Long, l: Listener): Outcome {
        return try {
            store.flush(d.path)
            store.commit(d.path)
            l.onFlushed(total)
            Outcome.Done(total)
        } catch (e: IOException) {
            Outcome.DiskError(e.message ?: "Could not finish the file")
        }
    }

    private fun stream(r: Response, d: HttpDownload, store: HomeStore, place: DownloadPlace, freeBytes: () -> Long, l: Listener, step: ResumeStep.Begin): Outcome {
        val body = r.body ?: return Outcome.Retry(FailKind.HTTP_BUSY, null, "Empty answer", false)
        val total = step.total
        val input = body.byteStream()
        val buf = ByteArray(BUFFER)
        var n = try {
            fill(input, buf)
        } catch (e: IOException) {
            return if (l.cancelled) Outcome.Cancelled else Outcome.Retry(FailKind.NETWORK, null, "Waiting for the connection", false)
        }
        // The guard sees the final address, the type, the real length and the first bytes, before anything is written.
        val verdict = HttpPlaceholderGuard.check(
            r.request.url.toString(), r.header("Content-Type"), total, d.expectedBytes, d.isTitle,
            if (step.from == 0L && n > 0) buf.copyOf(n) else null,
        )
        when (verdict) {
            is GuardVerdict.NotReady -> return Outcome.NotReady(verdict.stat)
            GuardVerdict.NotVideo -> return Outcome.NotVideo
            GuardVerdict.Ok -> Unit
        }
        try {
            // Starting over: the old part file (and pieces) must not leave stale bytes behind a shorter file.
            if (step.from == 0L) store.delete(d.path)
        } catch (e: IOException) {
            return Outcome.DiskError(e.message ?: "Could not clear the old file")
        }
        if (!HttpPlacement.fits(place, total - step.from, freeBytes())) return Outcome.NoSpace(total)
        l.onStart(total, step.validator, step.restarted || (step.from == 0L && d.doneBytes > 0))
        var pos = step.from
        var flushedAt = pos
        var flushedTime = now()
        var uiTime = 0L
        try {
            while (true) {
                if (l.cancelled) {
                    store.flush(d.path)
                    l.onFlushed(pos)
                    return Outcome.Cancelled
                }
                if (n < 0 || pos >= total) break
                val len = minOf(n.toLong(), total - pos).toInt()
                if (len > 0) {
                    store.write(d.path, total, pos, buf, 0, len)
                    pos += len
                }
                val t = now()
                if (t - uiTime >= UI_MS) {
                    l.onBytes(pos)
                    uiTime = t
                }
                if (pos - flushedAt >= flushBytes || t - flushedTime >= flushMs) {
                    store.flush(d.path)
                    l.onFlushed(pos)
                    flushedAt = pos
                    flushedTime = t
                }
                if (pos >= total) break
                n = try {
                    fill(input, buf)
                } catch (e: IOException) {
                    store.flush(d.path)
                    l.onFlushed(pos)
                    return if (l.cancelled) Outcome.Cancelled else Outcome.Retry(FailKind.NETWORK, null, "Waiting for the connection", pos > step.from)
                }
            }
            l.onBytes(pos)
        } catch (e: IOException) {
            // Only the store throws inside this block (reads are caught above): the disk, not the network.
            return Outcome.DiskError(e.message ?: "Could not write to the disk")
        }
        if (pos < total) {
            runCatching { store.flush(d.path); l.onFlushed(pos) }
            return Outcome.Retry(FailKind.NETWORK, null, "The connection ended early", pos > step.from)
        }
        return finish(store, d, total, l)
    }

    /** Reads until [buf] is full or the body ends, so the disk sees few large writes instead of one per socket read. -1 only at the very end. */
    private fun fill(input: java.io.InputStream, buf: ByteArray): Int {
        var t = 0
        while (t < buf.size) {
            // A dropped connection after some bytes: hand those over first (they get written), the next read throws again.
            val r = try { input.read(buf, t, buf.size - t) } catch (e: java.io.IOException) { if (t > 0) return t else throw e }
            if (r < 0) return if (t == 0) -1 else t
            t += r
        }
        return t
    }

    companion object {
        const val FLUSH_BYTES = 32L * 1024 * 1024
        const val FLUSH_MS = 10_000L
        private const val BUFFER = 128 * 1024
        private const val UI_MS = 250L
    }
}
