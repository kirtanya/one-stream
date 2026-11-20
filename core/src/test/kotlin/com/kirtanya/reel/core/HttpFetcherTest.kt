package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.HomeStore
import com.kirtanya.reel.core.homedisk.UsbStore
import com.kirtanya.reel.core.httpdl.FailKind
import com.kirtanya.reel.core.httpdl.HttpDownload
import com.kirtanya.reel.core.httpdl.HttpFetcher
import com.kirtanya.reel.core.httpdl.HttpFetcher.Outcome
import com.kirtanya.reel.core.stream.GiB
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fetch loop (docs/HOME_DISK.md §25.3) against a real local HTTP server and a [UsbStore] with a tiny piece size, so a
 * "4 GiB file" is a few hundred bytes in four pieces and every resume / seek case runs in milliseconds.
 */
class HttpFetcherTest {
    private val root: File = Files.createTempDirectory("httpdl").toFile()
    private val piece = 300L
    private val store = UsbStore(root, piece)
    private val data = Random(7).nextBytes(1000)

    /** What the server does for the next requests, and what it saw. */
    private class Script {
        var etag: String? = "\"v1\""
        var honourRange = true
        var cutAt: Long? = null            // the body stops after this many bytes (the connection drops)
        var status: Int? = null            // answer this and nothing else
        var retryAfter: String? = null
        var type = "video/mp4"
        var body: ByteArray = ByteArray(0)
        var redirectTo: String? = null
        var changeEtagOnRange: String? = null
        val ranges = CopyOnWriteArrayList<String?>()
        val ifRanges = CopyOnWriteArrayList<String?>()
        val userAgents = CopyOnWriteArrayList<String?>()
        val token = CopyOnWriteArrayList<String?>()
    }

    private val script = Script().also { it.body = data }

    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val s = script
            s.ranges += session.headers["range"]
            s.ifRanges += session.headers["if-range"]
            s.userAgents += session.headers["user-agent"]
            s.token += session.headers["x-token"]
            if (session.uri == "/clip.mp4") return body(Response.Status.OK, ByteArray(149_660), null, null)
            s.redirectTo?.let { to ->
                return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").also { it.addHeader("Location", to) }
            }
            s.status?.let { code ->
                val st = Response.Status.values().first { it.requestStatus == code }
                return newFixedLengthResponse(st, "text/plain", "x").also { r -> s.retryAfter?.let { r.addHeader("Retry-After", it) } }
            }
            val from = if (s.honourRange) session.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() else null
            val total = s.body.size.toLong()
            if (from != null) {
                if (from >= total) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also { it.addHeader("Content-Range", "bytes */$total") }
                val tag = s.changeEtagOnRange ?: s.etag
                return body(Response.Status.PARTIAL_CONTENT, s.body, from, tag).also { it.addHeader("Content-Range", "bytes $from-${total - 1}/$total") }
            }
            return body(Response.Status.OK, s.body, null, s.etag)
        }

        private fun body(st: Response.Status, body: ByteArray, from: Long?, tag: String?): Response {
            val s = script
            val start = (from ?: 0).toInt()
            val full = (body.size - start).toLong()
            val send = s.cutAt?.let { minOf(full, it) } ?: full
            val r = newFixedLengthResponse(st, s.type, ByteArrayInputStream(body, start, send.toInt()), full)
            tag?.let { r.addHeader("ETag", it) }
            if (send < full) r.closeConnection(true)
            return r
        }
    }

    init {
        server.start(5000, false)
    }

    @AfterTest fun clean() {
        server.stop()
        root.deleteRecursively()
    }

    private val url get() = "http://127.0.0.1:${server.listeningPort}/film.mp4"

    private fun dl(done: Long = 0, total: Long = 0, validator: String? = null, title: Boolean = false, listed: Long? = null, headers: Map<String, String> = emptyMap()) = HttpDownload(
        id = "t1", url = url, headers = headers, name = "Sample", fileName = "film.mp4", place = "usb", baseDir = root.path,
        expectedBytes = listed, totalBytes = total, doneBytes = done, validator = validator, isTitle = title, createdAt = 0,
    )

    private class Events : HttpFetcher.Listener {
        @Volatile override var cancelled = false
        @Volatile var onBytesHook: (Long) -> Unit = {}
        var started: Triple<Long, String?, Boolean>? = null
        var flushed = 0L
        override fun onCall(call: Call) = Unit
        override fun onStart(total: Long, validator: String?, restarted: Boolean) { started = Triple(total, validator, restarted) }
        override fun onBytes(done: Long) = onBytesHook(done)
        override fun onFlushed(done: Long) { flushed = done }
    }

    private val fetcher = HttpFetcher(OkHttpClient(), "test-ua", flushBytes = 100)

    private fun run(d: HttpDownload, ev: Events = Events(), st: HomeStore = store, free: Long = 100 * GiB, place: DownloadPlace = DownloadPlace.USB) =
        fetcher.fetch(d, st, place, { free }, ev)

    private fun committed(): ByteArray? {
        val size = store.size("t1/film.mp4") ?: return null
        val out = ByteArray(size.toInt())
        assertEquals(size.toInt(), store.read("t1/film.mp4", 0, out, 0, out.size))
        return out
    }

    private fun files() = root.walkTopDown().filter { it.isFile }.toList()

    @Test fun aFullDownloadIsSplitIntoPiecesAndReadsBackAsOneFile() {
        val ev = Events()
        assertEquals(Outcome.Done(1000), run(dl(), ev))
        assertEquals(Triple(1000L, "\"v1\"", false), ev.started)
        assertEquals(1000L, ev.flushed)
        assertContentEquals(data, committed())
        // 1000 bytes in 300-byte pieces: .001 .. .004, nothing left half written.
        assertEquals(listOf("film.mp4.001", "film.mp4.002", "film.mp4.003", "film.mp4.004"), File(root, "t1").list()!!.sorted())
        assertEquals("test-ua", script.userAgents.first())
        assertNull(script.ranges.first())
    }

    @Test fun seekingAcrossPieceBoundariesReturnsTheRightBytes() {
        run(dl())
        val rnd = Random(3)
        repeat(300) {
            val pos = rnd.nextInt(0, 1000)
            val len = rnd.nextInt(1, 450)
            val buf = ByteArray(len)
            val n = store.read("t1/film.mp4", pos.toLong(), buf, 0, len)
            val want = minOf(len, 1000 - pos)
            assertEquals(want, n, "pos=$pos len=$len")
            assertContentEquals(data.copyOfRange(pos, pos + want), buf.copyOf(want), "pos=$pos len=$len")
        }
        for (b in listOf(299, 300, 301, 599, 600, 601, 899, 900, 999)) {
            val buf = ByteArray(2)
            val n = store.read("t1/film.mp4", b.toLong(), buf, 0, 2)
            assertContentEquals(data.copyOfRange(b, minOf(1000, b + 2)), buf.copyOf(n), "boundary $b")
        }
        assertEquals(-1, store.read("t1/film.mp4", 1000, ByteArray(1)))
    }

    @Test fun aDroppedConnectionResumesWithARangeFromTheFlushedPosition() {
        script.cutAt = 450
        val ev = Events()
        val first = run(dl(), ev)
        assertIs<Outcome.Retry>(first)
        assertEquals(FailKind.NETWORK, first.kind)
        assertTrue(first.progressed)
        assertEquals(450L, ev.flushed, "everything that arrived is flushed before giving up")
        assertNull(store.size("t1/film.mp4"), "not committed yet")

        script.cutAt = null
        assertEquals(Outcome.Done(1000), run(dl(done = ev.flushed, total = 1000, validator = "\"v1\""), Events()))
        assertEquals("bytes=450-", script.ranges.last())
        assertEquals("\"v1\"", script.ifRanges.last())
        assertContentEquals(data, committed())
    }

    @Test fun resumingOnAPieceBoundaryAndInTheMiddleOfAPieceBothWork() {
        for (cut in listOf(300L, 600L, 301L, 599L, 999L, 1L)) {
            File(root, "t1").deleteRecursively()
            script.cutAt = cut
            val ev = Events()
            run(dl(), ev)
            script.cutAt = null
            assertEquals(Outcome.Done(1000), run(dl(done = ev.flushed, total = 1000, validator = "\"v1\""), Events()), "cut=$cut")
            assertContentEquals(data, committed(), "cut=$cut")
        }
    }

    @Test fun aServerThatIgnoresRangeSendsEverythingAgainAndTheOldPartIsReplaced() {
        script.cutAt = 450
        val ev = Events()
        run(dl(), ev)
        script.cutAt = null
        script.honourRange = false
        val ev2 = Events()
        assertEquals(Outcome.Done(1000), run(dl(done = ev.flushed, total = 1000, validator = "\"v1\""), ev2))
        assertEquals(true, ev2.started?.third, "restarted")
        assertContentEquals(data, committed())
    }

    @Test fun aChangedFileOnTheServerIsFetchedAgainFromZero() {
        script.cutAt = 450
        val ev = Events()
        run(dl(), ev)
        script.cutAt = null
        script.changeEtagOnRange = "\"v2\""
        assertEquals(Outcome.Done(1000), run(dl(done = ev.flushed, total = 1000, validator = "\"v1\""), Events()))
        assertEquals(listOf("bytes=450-", null), script.ranges.takeLast(2), "ranged first, then a plain request")
        assertContentEquals(data, committed())
    }

    @Test fun aPartFileThatIsGoneStartsOverInsteadOfWritingIntoAHole() {
        assertEquals(Outcome.Done(1000), run(dl(done = 450, total = 1000, validator = "\"v1\"")))
        assertNull(script.ranges.first(), "no Range: the saved position meant nothing")
        assertContentEquals(data, committed())
    }

    @Test fun aRestartAfterTheCommitButBeforeTheStateWasSavedStillEndsComplete() {
        run(dl())
        // The row still says 1000 of 1000 but the part file was renamed away by the commit: start over, end complete.
        assertEquals(Outcome.Done(1000), run(dl(done = 1000, total = 1000), Events()))
        assertContentEquals(data, committed())
    }

    @Test fun busyAnswersCarryRetryAfter() {
        script.status = 429
        script.retryAfter = "7"
        val out = run(dl())
        assertIs<Outcome.Retry>(out)
        assertEquals(FailKind.HTTP_BUSY, out.kind)
        assertEquals(7L, out.retryAfterSec)
        assertFalse(out.progressed)
        script.status = 503
        assertIs<Outcome.Retry>(run(dl()))
    }

    @Test fun finalAnswersFailWithAPlainLine() {
        script.status = 404
        assertEquals(Outcome.Failed("The link is gone. Pick another source"), run(dl()))
        script.status = 403
        assertIs<Outcome.Failed>(run(dl()))
    }

    @Test fun aRefusedConnectionIsANetworkRetry() {
        val d = dl()
        server.stop()
        val out = run(d)
        assertIs<Outcome.Retry>(out)
        assertEquals(FailKind.NETWORK, out.kind)
    }

    @Test fun theSourcesHeadersArePassedThrough() {
        run(dl(headers = mapOf("X-Token" to "abc", "User-Agent" to "mine")))
        assertEquals("abc", script.token.first())
        assertEquals("mine", script.userAgents.first())
    }

    @Test fun theStubAfterARedirectIsNotReadyAndNothingIsLeftOnTheDisk() {
        script.redirectTo = "/clip.mp4"
        assertEquals(Outcome.NotReady("stub"), run(dl(title = true, listed = 4 * GiB)))
        assertEquals(emptyList(), files())
    }

    @Test fun aTinyFileForAFilmIsNotReady() {
        script.body = ByteArray(5000)
        assertEquals(Outcome.NotReady("tiny"), run(dl(title = true)))
        assertEquals(emptyList(), files())
    }

    @Test fun anErrorPageIsNotAVideo() {
        script.type = "text/html"
        script.body = "<html>nope</html>".toByteArray()
        assertEquals(Outcome.NotVideo, run(dl()))
        script.type = "application/octet-stream"
        assertEquals(Outcome.NotVideo, run(dl()))
        assertEquals(emptyList(), files())
    }

    @Test fun noRoomStopsBeforeWritingAnything() {
        assertEquals(Outcome.NoSpace(1000), run(dl(), free = 2_000_000_000L + 999))
        assertEquals(emptyList(), files())
    }

    @Test fun theTvPlaceKeepsItsOwnFloorAndNeverSplits() {
        val whole = UsbStore(File(root, "tv"), Long.MAX_VALUE / 4)
        assertEquals(Outcome.Done(1000), fetcher.fetch(dl(), whole, DownloadPlace.TV, { GiB + 1000 }, Events()))
        assertEquals(listOf("film.mp4"), File(root, "tv/t1").list()!!.toList())
        assertEquals(Outcome.NoSpace(1000), fetcher.fetch(dl(), UsbStore(File(root, "tv2"), Long.MAX_VALUE / 4), DownloadPlace.TV, { GiB + 999 }, Events()))
    }

    @Test fun pausingStopsAtOnceKeepsWhatArrivedAndResumesToTheEnd() {
        script.body = ByteArray(3_000_000) { it.toByte() }
        val st = UsbStore(root, 1_000_000)
        val ev = Events()
        ev.onBytesHook = { ev.cancelled = true }
        assertEquals(Outcome.Cancelled, fetcher.fetch(dl(), st, DownloadPlace.USB, { 100 * GiB }, ev))
        assertTrue(ev.flushed > 0 && ev.flushed < 3_000_000, "flushed ${ev.flushed}")
        assertEquals(Outcome.Done(3_000_000), fetcher.fetch(dl(done = ev.flushed, total = 3_000_000, validator = "\"v1\""), st, DownloadPlace.USB, { 100 * GiB }, Events()))
        val got = ByteArray(3_000_000)
        assertEquals(3_000_000, st.read("t1/film.mp4", 0, got))
        assertContentEquals(script.body, got)
    }

    @Test fun aDiskThatFailsIsADiskErrorNotANetworkOne() {
        val broken = object : HomeStore by store {
            override fun write(path: String, totalSize: Long, pos: Long, buf: ByteArray, off: Int, len: Int) = throw IOException("I/O error")
        }
        assertEquals(Outcome.DiskError("I/O error"), run(dl(), st = broken))
    }
}
