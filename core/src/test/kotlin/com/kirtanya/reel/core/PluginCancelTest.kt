package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.PluginRuntime
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.tmdb.MediaType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** R4 report 20261118-192421: a plugin run cancelled mid-fetch aborted the process inside QuickJS. */
class PluginCancelTest {
    private val code = File("../app/src/main/assets/plugins/internet-archive.js").readText()
    private val req = SourceRequest(10331, "tt0063350", MediaType.MOVIE, "Night of the Living Dead", 1968)

    @Test fun cancellingAMidFetchRunStaysACancellationAndSettlesTheFetch(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val fetchEnded = AtomicBoolean(false)
        val sawCancel = AtomicBoolean(false)
        val http = HttpFetcher {
            started.complete(Unit)
            try { awaitCancellation() } catch (e: CancellationException) { sawCancel.set(true); throw e } finally { fetchEnded.set(true) }
        }
        val job = async { PluginRuntime(http).getStreams(code, "IA", req) }
        withTimeout(10_000) { started.await() }
        job.cancel()
        val e = runCatching { withTimeout(10_000) { job.await() } }.exceptionOrNull()
        assertTrue(e is CancellationException, "was $e")
        assertTrue(sawCancel.get(), "the fetch saw the cancellation instead of returning an error body")
        assertTrue(fetchEnded.get(), "run() returned only after the host call settled")
    }

    @Test fun aTimeoutIsStillAPluginFailure() {
        val http = HttpFetcher { awaitCancellation() }
        val ex = assertFailsWith<com.kirtanya.reel.core.sources.PluginException> {
            runBlocking { PluginRuntime(http, timeoutMs = 300).getStreams(code, "IA", req) }
        }
        assertEquals("Took longer than 0s", ex.message)
    }
}
