package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.OkHttpFetcher
import com.kirtanya.reel.core.sync.AckState
import com.kirtanya.reel.core.sync.ApplyResult
import com.kirtanya.reel.core.sync.HomeAgent
import com.kirtanya.reel.core.sync.HomeCancel
import com.kirtanya.reel.core.sync.HomeCancelReply
import com.kirtanya.reel.core.sync.HomeDownload
import com.kirtanya.reel.core.sync.HomeDownloadAck
import com.kirtanya.reel.core.sync.HomeJobsQuery
import com.kirtanya.reel.core.sync.HomeJobsReply
import com.kirtanya.reel.core.sync.HomeOps
import com.kirtanya.reel.core.sync.HomeRpc
import com.kirtanya.reel.core.sync.HomeSummary
import com.kirtanya.reel.core.sync.JobState
import com.kirtanya.reel.core.sync.PairedPhone
import com.kirtanya.reel.core.sync.RpcReply
import com.kirtanya.reel.core.sync.RpcRequest
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.sync.SyncClient
import com.kirtanya.reel.core.sync.SyncException
import com.kirtanya.reel.core.sync.SyncHost
import com.kirtanya.reel.core.sync.SyncProtocol
import com.kirtanya.reel.core.sync.SyncServer
import com.kirtanya.reel.core.sync.TvCredentials
import com.kirtanya.reel.core.sync.TvHomeState
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BBB = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny"
private const val SINTEL = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"

private fun download(id: String, magnet: String = BBB, fileIdx: Int? = 0, full: Boolean = false) =
    HomeRpc.request(id, HomeOps.DOWNLOAD, HomeDownload.serializer(), HomeDownload(magnet, fileIdx = fileIdx, fullTorrent = full, label = "Big Buck Bunny"))

private fun ack(r: RpcReply) = HomeRpc.bodyOf(r, HomeDownloadAck.serializer())!!

class HomeAgentTest {
    @Test fun acceptedAndWaitingForHomeStorage() {
        val (s, r) = HomeAgent.handle(TvHomeState(), download("r1"), now = 10)
        assertTrue(r.ok)
        val a = ack(r)
        assertEquals(AckState.QUEUED, a.state)
        assertEquals(JobState.WAITING_DISK, a.jobState)
        assertEquals(HomeAgent.WAITING_FOR_STORAGE, a.reason)
        assertEquals(1, s.jobs.size)
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", s.jobs[0].key)
    }

    @Test fun sameIdTwiceIsOneJobAndTheSameAnswer() {
        val (s1, r1) = HomeAgent.handle(TvHomeState(), download("r1"), now = 10)
        val (s2, r2) = HomeAgent.handle(s1, download("r1"), now = 20)
        assertEquals(r1, r2)
        assertEquals(1, s2.jobs.size)
        assertEquals(listOf("r1"), s2.jobs[0].requestIds)
    }

    @Test fun anotherIdForTheSameKeyMergesWantedFiles() {
        val (s1, _) = HomeAgent.handle(TvHomeState(), download("r1", fileIdx = 0), now = 10)
        val (s2, r2) = HomeAgent.handle(s1, download("r2", fileIdx = 3), now = 20)
        assertEquals(AckState.DUPLICATE, ack(r2).state)
        assertEquals(ack(HomeAgent.handle(TvHomeState(), download("x"), 1).second).jobId, ack(r2).jobId)
        assertEquals(setOf(0, 3), s2.jobs.single().files)
        // Never narrowed: a full-torrent job stays full.
        val (s3, _) = HomeAgent.handle(s2, download("r3", full = true), now = 30)
        assertNull(s3.jobs.single().files)
        val (s4, _) = HomeAgent.handle(s3, download("r4", fileIdx = 1), now = 40)
        assertNull(s4.jobs.single().files)
        assertEquals(2, HomeAgent.handle(s4, download("r5", magnet = SINTEL), 50).first.jobs.size)
    }

    @Test fun cancelThenAskAgainIsANewJob() {
        val (s1, r1) = HomeAgent.handle(TvHomeState(), download("r1"), now = 10)
        val cancel = HomeRpc.request("c1", HomeOps.CANCEL, HomeCancel.serializer(), HomeCancel(ack(r1).jobId))
        val (s2, rc) = HomeAgent.handle(s1, cancel, now = 20)
        assertEquals(JobState.CANCELLED, HomeRpc.bodyOf(rc, HomeCancelReply.serializer())!!.state)
        val (s3, r3) = HomeAgent.handle(s2, download("r2"), now = 30)
        assertEquals(AckState.QUEUED, ack(r3).state)
        assertEquals(2, s3.jobs.size)
    }

    @Test fun jobsListProgressAndQueue() {
        var s = HomeAgent.handle(TvHomeState(), download("r1"), now = 10).first
        s = HomeAgent.handle(s, download("r2", magnet = SINTEL), now = 20).first
        val (_, r) = HomeAgent.handle(s, HomeRpc.request("q", HomeOps.JOBS, HomeJobsQuery.serializer(), HomeJobsQuery(0)), now = 30)
        val jobs = HomeRpc.bodyOf(r, HomeJobsReply.serializer())!!.jobs
        assertEquals(listOf(1, 2), jobs.map { it.queuePos })
        assertTrue(jobs.all { it.state == JobState.WAITING_DISK })
        val (_, since) = HomeAgent.handle(s, HomeRpc.request("q2", HomeOps.JOBS, HomeJobsQuery.serializer(), HomeJobsQuery(15)), now = 30)
        assertEquals(1, HomeRpc.bodyOf(since, HomeJobsReply.serializer())!!.jobs.size)
        assertEquals(2, HomeAgent.summary(s, "NotConnected", null).activeJobs)
    }

    @Test fun unknownOpAndBadLinks() {
        val (_, r) = HomeAgent.handle(TvHomeState(), RpcRequest("u", "home.fly"), now = 1)
        assertFalse(r.ok)
        assertTrue(r.error!!.startsWith("Unknown operation"))
        val (_, bad) = HomeAgent.handle(TvHomeState(), download("b", magnet = "not a magnet"), now = 1)
        assertEquals(AckState.REJECTED, ack(bad).state)
        val (_, garbage) = HomeAgent.handle(TvHomeState(), RpcRequest("g", HomeOps.DOWNLOAD), now = 1)
        assertFalse(garbage.ok)
    }

    @Test fun oldRequestIdsAreForgottenAfter30Days() {
        val (s1, _) = HomeAgent.handle(TvHomeState(), download("r1"), now = 0)
        val (s2, _) = HomeAgent.handle(s1, RpcRequest("x", HomeOps.LIBRARY_CHANGED), now = HomeAgent.KEEP_REQUESTS_MS + 1)
        assertTrue(s2.requests.isEmpty())
    }
}

/** v3 over real HTTP on localhost: verified, encrypted, idempotent. */
class HomeRpcServerTest {
    private class Tv : SyncHost {
        override val deviceName = "TV"
        override val tvId = "tv-1"
        val phones = mutableListOf<PairedPhone>()
        var state = TvHomeState()
        override suspend fun apply(bundle: SourcesBundle) = ApplyResult("ok")
        override fun pairedPhones() = phones
        override fun savePairedPhone(phone: PairedPhone) { phones += phone }
        override fun addresses() = listOf("192.168.1.128", "100.101.102.103")
        override fun homeSummary() = HomeSummary("NotConnected", null, state.jobs.count { it.active })
        override suspend fun rpc(phone: PairedPhone, request: RpcRequest): RpcReply {
            val (s, r) = HomeAgent.handle(state, request, System.currentTimeMillis())
            state = s
            return r
        }
    }

    private val client = SyncClient(OkHttpFetcher(OkHttpClient()))

    @Test fun downloadAtHomeRoundTrip() {
        val tv = Tv()
        val port = ServerSocket(0).use { it.localPort }
        val server = SyncServer(tv, port).apply { start(5_000, true) }
        try {
            runBlocking {
                val base = "http://127.0.0.1:$port"
                server.openPairing()
                val p = client.pair(base, server.code, "Pixel 8")
                val creds = TvCredentials(p.tvId, p.phoneId, p.secret)
                val hello = client.verifiedHello(base, creds)
                assertEquals(SyncProtocol.VERSION, hello.protocol)
                assertEquals(listOf("192.168.1.128", "100.101.102.103"), hello.addrs)
                val r1 = client.rpc(base, creds, download("req-1"))
                val r2 = client.rpc(base, creds, download("req-1")) // retry after a lost reply
                assertEquals(r1, r2)
                assertEquals(1, tv.state.jobs.size)
                assertEquals(HomeAgent.WAITING_FOR_STORAGE, ack(r1).reason)
                // A wrong secret gets nothing.
                assertFailsWith<SyncException> { client.rpc(base, creds.copy(secret = "00".repeat(32)), download("req-2")) }
                assertEquals(1, tv.state.jobs.size)
            }
        } finally {
            server.stop()
        }
    }

    @Test fun sourceFilter() {
        assertTrue(SyncProtocol.allowedPeer("127.0.0.1"))
        assertTrue(SyncProtocol.allowedPeer("100.100.1.1"))
        assertFalse(SyncProtocol.allowedPeer("203.0.113.9"))
    }
}
