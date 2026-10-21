package com.kirtanya.reel.homedisk

import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.ManifestOrigin
import com.kirtanya.reel.core.homedisk.Outbox
import com.kirtanya.reel.core.homedisk.OutboxItem
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.sync.AckState
import com.kirtanya.reel.core.sync.HomeCancel
import com.kirtanya.reel.core.sync.HomeConfig
import com.kirtanya.reel.core.sync.HomeConfigReply
import com.kirtanya.reel.core.sync.HomeDownload
import com.kirtanya.reel.core.sync.HomeDownloadAck
import com.kirtanya.reel.core.sync.HomeJobsQuery
import com.kirtanya.reel.core.sync.HomeJobsReply
import com.kirtanya.reel.core.sync.HomeOps
import com.kirtanya.reel.core.sync.HomeRpc
import com.kirtanya.reel.core.sync.JobState
import com.kirtanya.reel.core.sync.RpcReply
import com.kirtanya.reel.core.sync.RpcRequest
import com.kirtanya.reel.core.sync.SyncException
import com.kirtanya.reel.data.sync.PairedTv
import com.kirtanya.reel.engine.EngineStats
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** An at-home download as the phone knows it. [state] null = the TV hasn't acknowledged it yet. */
@Serializable
data class PhoneJob(
    val requestId: String,
    val label: String,
    val key: String,
    val origin: ManifestOrigin? = null,
    val jobId: String? = null,
    val state: JobState? = null,
    val percent: Int = 0,
    val queuePos: Int? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The phone's side of "Download at home" (docs/HOME_DISK.md §9.3): requests go into the outbox
 * (`files/home/outbox.json`) and are sent to the paired TV on app start, network changes, when Downloads opens
 * and every minute while something waits; the TV's answers and job states land in `files/home/jobs.json`.
 * Phase 2: the TV answers "Accepted, waiting for home storage".
 */
class HomeClient(private val app: ReelApp, private val home: Home) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outboxFile = Home.file(app, "outbox.json")
    private val jobsFile = Home.file(app, "jobs.json")
    private val jobsCodec = ListSerializer(PhoneJob.serializer())
    private val sendLock = Mutex()

    private val _outbox = MutableStateFlow(Outbox.decode(Home.read(outboxFile)))
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()
    private val _jobs = MutableStateFlow(Home.read(jobsFile)?.let { runCatching { json.decodeFromString(jobsCodec, it) }.getOrNull() }.orEmpty())
    val jobs: StateFlow<List<PhoneJob>> = _jobs.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun start() {
        app.appScope.launch { flush(force = true) }
        app.appScope.launch {
            home.network.now.map { it.fingerprint }.distinctUntilChanged().drop(1).collect { flush(force = true) }
        }
        app.appScope.launch {
            while (isActive) {
                delay(60_000)
                if (_outbox.value.isNotEmpty()) flush(force = false)
                if (_jobs.value.any { it.state != null && it.state !in DONE_STATES }) poll()
            }
        }
    }

    fun pairedTv(): PairedTv? = app.phoneSync.state.value.tvs.firstOrNull { !it.needsPairing && it.secret.isNotEmpty() }

    /** "Download at home": queued in the outbox and sent straight away when the TV answers. Returns the request id. */
    fun requestDownload(magnet: String, fileIdx: Int?, fileHint: String?, label: String, origin: DownloadOrigin?): String {
        val id = UUID.randomUUID().toString()
        val o = origin?.let { ManifestOrigin(it.type, it.tmdbId, it.season, it.episode, it.label) }
        val body = HomeDownload(magnet, fileIdx = fileIdx, fileHint = fileHint, fullTorrent = fileIdx == null && fileHint == null, origin = o, requestedAt = System.currentTimeMillis(), label = label)
        val now = System.currentTimeMillis()
        val key = Magnet.parse(magnet)?.infoHash?.lowercase().orEmpty()
        setJobs(_jobs.value + PhoneJob(id, label, key, o, createdAt = now, updatedAt = now))
        enqueue(HomeRpc.request(id, HomeOps.DOWNLOAD, HomeDownload.serializer(), body))
        return id
    }

    /** Cancel an at-home download (or drop a request that never reached the TV). */
    fun cancel(job: PhoneJob) {
        if (job.jobId == null) {
            setOutbox(Outbox.acknowledged(_outbox.value, job.requestId))
            setJobs(_jobs.value.filterNot { it.requestId == job.requestId })
            return
        }
        enqueue(HomeRpc.request(UUID.randomUUID().toString(), HomeOps.CANCEL, HomeCancel.serializer(), HomeCancel(job.jobId)))
    }

    /** Remove a finished / cancelled job from the list. */
    fun forget(job: PhoneJob) = setJobs(_jobs.value.filterNot { it.requestId == job.requestId })

    /**
     * Sends the disk settings to the TV (`home.config`). The password is added only at send time: the outbox
     * on disk never holds it. Returns the TV's Test line, or why it couldn't be sent yet.
     */
    suspend fun sendConfig(): String {
        val s = home.settings.value
        val body = HomeConfig(s.address, s.share, s.user, null, s.smb1, s.retention.days, s.retention.cacheSharePct, s.retention, atHome = home.disk.ui.value.home?.isHome == true)
        // One pending config at a time: the newest replaces any older one.
        setOutbox(_outbox.value.filterNot { it.request.op == HomeOps.CONFIG })
        val req = HomeRpc.request(UUID.randomUUID().toString(), HomeOps.CONFIG, HomeConfig.serializer(), body)
        enqueue(req, flush = false)
        flush(force = true)
        val reply = lastReplies[req.id] ?: return _lastError.value?.let { "Not sent yet: $it. It will be sent when your TV is reachable." }
            ?: "Not sent yet. It will be sent when your TV is reachable."
        if (!reply.ok) return reply.error ?: "The TV couldn't take the settings"
        return HomeRpc.bodyOf(reply, HomeConfigReply.serializer())?.test?.let { "TV: $it" } ?: "Sent to your TV"
    }

    private val lastReplies = HashMap<String, RpcReply>()

    private fun enqueue(req: RpcRequest, flush: Boolean = true) {
        setOutbox(Outbox.add(_outbox.value, req, System.currentTimeMillis()))
        if (flush) app.appScope.launch { flush(force = true) }
    }

    /** Sends what is due. [force]: app start, network change, Downloads opened, a new request. */
    suspend fun flush(force: Boolean) = sendLock.withLock {
        val tv = pairedTv() ?: return@withLock
        val due = Outbox.due(_outbox.value, System.currentTimeMillis(), force)
        dropExpired()
        if (due.isEmpty()) return@withLock
        val base = "http://${tv.host}:${tv.port}"
        var sent = 0
        for (item in due) {
            val started = EngineStats.now()
            val request = withPassword(item.request)
            val reply = try {
                home.sync.rpc(base, tv.credentials, request)
            } catch (e: SyncException) {
                fail(item, e.message ?: "Couldn't reach your TV")
                // One unreachable TV fails the rest too: stop here and keep their backoff as it is.
                break
            } catch (e: Exception) {
                fail(item, "Couldn't reach your TV")
                break
            }
            EngineStats.log("rpc", linkedMapOf("op" to item.request.op, "via" to "lan", "ms" to EngineStats.now() - started, "ok" to if (reply.ok) 1 else 0))
            lastReplies[item.request.id] = reply
            setOutbox(Outbox.acknowledged(_outbox.value, item.request.id))
            onReply(item.request, reply)
            sent++
        }
        if (sent > 0) {
            _lastError.value = null
            EngineStats.log("outbox", linkedMapOf("sent" to sent, "waitedMin" to due.maxOf { (System.currentTimeMillis() - it.createdAt) / 60_000 }))
        }
    }

    /** Polls `home.jobs` for progress and Done. */
    suspend fun poll() {
        val tv = pairedTv() ?: return
        val req = HomeRpc.request(UUID.randomUUID().toString(), HomeOps.JOBS, HomeJobsQuery.serializer(), HomeJobsQuery(0))
        val reply = runCatching { home.sync.rpc("http://${tv.host}:${tv.port}", tv.credentials, req) }.getOrNull() ?: return
        val rows = HomeRpc.bodyOf(reply, HomeJobsReply.serializer())?.jobs ?: return
        val now = System.currentTimeMillis()
        setJobs(_jobs.value.map { j ->
            val row = rows.firstOrNull { it.jobId == j.jobId } ?: return@map j
            j.copy(state = row.state, percent = row.percent, queuePos = row.queuePos, note = row.error, updatedAt = now)
        })
    }

    private fun onReply(request: RpcRequest, reply: RpcReply) {
        val now = System.currentTimeMillis()
        when (request.op) {
            HomeOps.DOWNLOAD -> {
                val ack = HomeRpc.bodyOf(reply, HomeDownloadAck.serializer())
                setJobs(_jobs.value.map { j ->
                    if (j.requestId != request.id) j
                    else if (ack == null || ack.state == AckState.REJECTED) j.copy(state = JobState.FAILED, note = ack?.reason ?: reply.error, updatedAt = now)
                    else j.copy(jobId = ack.jobId, state = ack.jobState ?: JobState.QUEUED, note = ack.reason, updatedAt = now)
                })
                Log.i(TAG, "at home: ${ack?.state} ${ack?.reason ?: ""}")
            }
            HomeOps.CANCEL -> {
                val jobId = runCatching { HomeRpc.bodyOf(request, HomeCancel.serializer()).jobId }.getOrNull()
                if (reply.ok) setJobs(_jobs.value.map { if (it.jobId == jobId) it.copy(state = JobState.CANCELLED, updatedAt = now) else it })
            }
        }
    }

    private fun fail(item: OutboxItem, error: String) {
        _lastError.value = error
        setOutbox(Outbox.failed(_outbox.value, item.request.id, System.currentTimeMillis(), error))
    }

    private fun dropExpired() {
        val now = System.currentTimeMillis()
        val gone = _outbox.value.filter { Outbox.expired(it, now) }
        if (gone.isEmpty()) return
        setOutbox(_outbox.value - gone.toSet())
        val ids = gone.mapTo(HashSet()) { it.request.id }
        setJobs(_jobs.value.map { if (it.requestId in ids && it.state == null) it.copy(state = JobState.FAILED, note = "Couldn't reach your TV for a week") else it })
    }

    /** `home.config` gets the password from the Keystore at send time only. */
    private fun withPassword(r: RpcRequest): RpcRequest {
        if (r.op != HomeOps.CONFIG) return r
        val pw = home.password() ?: return r
        return r.copy(body = JsonObject(r.body.jsonObject + ("password" to JsonPrimitive(pw))))
    }

    private fun setOutbox(items: List<OutboxItem>) {
        _outbox.value = items
        Home.write(outboxFile, Outbox.encode(items))
    }

    private fun setJobs(list: List<PhoneJob>) {
        _jobs.value = list
        Home.write(jobsFile, json.encodeToString(jobsCodec, list))
    }

    companion object {
        private const val TAG = "ReelHome"
        val DONE_STATES = setOf(JobState.DONE, JobState.FAILED, JobState.CANCELLED)
    }
}
