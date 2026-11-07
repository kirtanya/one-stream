package com.kirtanya.reel.data

import com.kirtanya.reel.core.sources.QuickStart
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.StartProbe
import com.kirtanya.reel.engine.EngineStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Quick start's live check of direct links (QuickStart.kt): fetches the first megabyte of a link the way the player will
 * (its own headers, a Range request) and measures how soon the first byte comes and how fast the rest follows.
 * Plain HTTP only: torrents are never touched here (no BitTorrent traffic outside the devices at home).
 *
 * One probe has a 4 s budget in total, at most [PARALLEL] run at once, and the answer is kept [TTL_MS] per
 * [QuickStart.key]. Probes run in this object's own scope, so a screen that leaves (or a search that reshuffles the
 * list) doesn't throw away work in flight: the next screen asking for the same link joins it.
 */
object SourceProbe {
    private const val BUDGET_MS = 4_000L
    private const val MAX_BYTES = 1_048_576
    /** Fewer bytes than this before the body ends: not a measurable answer (see probe). */
    private const val MIN_BYTES = 256 * 1024L
    private const val TTL_MS = 10 * 60 * 1000L
    private const val PARALLEL = 4

    private class Cached(val at: Long, val probe: StartProbe)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inflight = ConcurrentHashMap<String, Deferred<StartProbe>>()

    /** Whether [s] is a link this can probe: a direct http(s) URL. */
    fun probeable(s: Source): Boolean =
        s.kind == SourceKind.DIRECT && s.url?.let { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) } == true

    /** What the last probe of [s] found, if it's still fresh. */
    fun cached(s: Source): StartProbe? {
        val key = QuickStart.key(s)
        val hit = cache[key] ?: return null
        if (now() - hit.at > TTL_MS) { cache.remove(key, hit); return null }
        return hit.probe
    }

    /** Probes [s] (or returns the fresh cached answer); null for anything that isn't a direct link. */
    suspend fun probe(http: OkHttpClient, s: Source): StartProbe? {
        if (!probeable(s)) return null
        cached(s)?.let { return it }
        val key = QuickStart.key(s)
        val job = inflight.computeIfAbsent(key) {
            scope.async {
                try {
                    gate.withPermit { fetch(http, s).also { cache[key] = Cached(now(), it) } }
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return job.await()
    }

    private fun now() = System.currentTimeMillis()

    /** One blocking probe: time to the first body byte, throughput over what arrives until 1 MB or the budget. */
    private fun fetch(http: OkHttpClient, s: Source): StartProbe {
        val client = http.newBuilder()
            .connectTimeout(BUDGET_MS, TimeUnit.MILLISECONDS)
            .readTimeout(BUDGET_MS, TimeUnit.MILLISECONDS)
            // The whole call, body included: a stalled read can't hold a probe (or its permit) past the budget.
            .callTimeout(BUDGET_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = runCatching {
            Request.Builder().url(s.url!!).apply {
                s.headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=0-${MAX_BYTES - 1}")
            }.build()
        }.getOrNull() ?: return StartProbe(failed = true)

        val start = System.nanoTime()
        var total = 0L
        var firstByteNs = 0L
        var endNs = 0L
        var code = -1
        var type: String? = null
        var ended = false
        val result = try {
            client.newCall(request).execute().use { response ->
                code = response.code
                type = response.header("Content-Type")
                val html = type?.contains("html", ignoreCase = true) == true
                if (response.code >= 400 || html) return@use StartProbe(failed = true)
                val body = response.body ?: return@use StartProbe(failed = true)
                val buffer = ByteArray(16 * 1024)
                val input = body.byteStream()
                while (total < MAX_BYTES) {
                    if ((System.nanoTime() - start) / 1_000_000 >= BUDGET_MS) break
                    val n = input.read(buffer)
                    if (n < 0) { ended = true; break }
                    if (total == 0L && n > 0) firstByteNs = System.nanoTime()
                    total += n
                    endNs = System.nanoTime()
                }
                // The body ended long before the 1 MB asked for: not the film's bytes (a placeholder, an error answer,
                // a redirect page), so no speed can be read from it. Seen on the TV: six links all "489 kB/s" = ~24 KB.
                if (ended && total < MIN_BYTES) StartProbe() else measured(start, firstByteNs, endNs, total)
            }
        } catch (e: IOException) {
            // Cut off by the budget after some bytes arrived: that's a (slow) measurement, not an error.
            if (total > 0) measured(start, firstByteNs, endNs, total) else StartProbe(failed = true)
        } catch (e: RuntimeException) {
            StartProbe(failed = true)
        }
        EngineStats.log(
            "quickprobe",
            linkedMapOf(
                "provider" to s.provider, "ttfbMs" to result.ttfbMs, "kBps" to result.bytesPerSec?.div(1000), "failed" to result.failed,
                "code" to code, "type" to type?.substringBefore(';'), "bytes" to total, "ended" to ended,
            ),
        )
        return result
    }

    private fun measured(startNs: Long, firstByteNs: Long, endNs: Long, total: Long): StartProbe {
        if (total <= 0 || firstByteNs == 0L) return StartProbe(failed = true)
        val ttfbMs = (firstByteNs - startNs) / 1_000_000
        // Transfer time after the first byte; a floor so a tiny, instant answer doesn't read as infinite speed.
        val transferMs = ((endNs - firstByteNs) / 1_000_000).coerceAtLeast(50)
        return StartProbe(ttfbMs = ttfbMs, bytesPerSec = total * 1000 / transferMs)
    }
}
