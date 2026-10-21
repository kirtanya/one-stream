package com.kirtanya.reel.core.sync

import com.kirtanya.reel.core.homedisk.ManifestOrigin
import com.kirtanya.reel.core.homedisk.RetentionSettings
import com.kirtanya.reel.core.magnet.Magnet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Sync protocol v3: paired phone → TV requests for the home disk (docs/HOME_DISK.md §9). One endpoint,
 * `POST /sync/v3/rpc`, body AES-GCM(secret, [RpcRequest], aad = nonce), reply AES-GCM(secret, [RpcReply],
 * aad = "reply:" + nonce). [RpcRequest.id] (a UUID) is the idempotency key: the phone resends the same id
 * until the TV answers, and the TV answers a repeated id with the same job.
 */
@Serializable
data class RpcRequest(val id: String, val op: String, val body: JsonElement = JsonNull)

@Serializable
data class RpcReply(val id: String, val ok: Boolean, val body: JsonElement? = null, val error: String? = null)

object HomeOps {
    const val DOWNLOAD = "home.download"
    const val JOBS = "home.jobs"
    const val CANCEL = "home.cancel"
    const val LIBRARY = "home.library"
    const val DELETE = "home.delete"
    const val STREAM = "home.stream"
    const val CONFIG = "home.config"
    const val LIBRARY_CHANGED = "home.libraryChanged"
}

@Serializable enum class HomeDest { LIBRARY, CACHE }

/** "Download at home": the request. */
@Serializable
data class HomeDownload(
    val magnet: String? = null,
    /** A .torrent only when ≤ 200 KiB; otherwise the TV fetches metadata from the magnet. */
    val torrentB64: String? = null,
    val fileIdx: Int? = null,
    val fileHint: String? = null,
    val fullTorrent: Boolean = false,
    val origin: ManifestOrigin? = null,
    val dest: HomeDest = HomeDest.LIBRARY,
    val requestedAt: Long = 0,
    /** What the phone shows for it ("Dune: Part Two", the episode). */
    val label: String = "",
)

/** How the TV took a request. */
@Serializable enum class AckState { QUEUED, STARTED, DUPLICATE, REJECTED }

/** "Download at home": the acknowledgement. */
@Serializable
data class HomeDownloadAck(val jobId: String, val state: AckState, val reason: String? = null, val jobState: JobState? = null)

/** A job's state on the TV (progress and done arrive through `home.jobs`). */
@Serializable
enum class JobState { QUEUED, METADATA, DOWNLOADING, VERIFYING, DONE, WAITING_DISK, DISK_FULL, WAITING_SPACE, FAILED, CANCELLED }

/** "Download at home": progress and done (one row of `home.jobs`). */
@Serializable
data class HomeJob(
    val jobId: String,
    val requestId: String,
    val key: String,
    val label: String,
    val state: JobState,
    val bytesOnDisk: Long = 0,
    val wantedBytes: Long = 0,
    val downBps: Long = 0,
    val etaSec: Long? = null,
    val queuePos: Int? = null,
    val error: String? = null,
    val updatedAt: Long = 0,
) {
    val percent: Int get() = if (wantedBytes <= 0) 0 else (bytesOnDisk * 100 / wantedBytes).toInt().coerceIn(0, 100)
}

@Serializable data class HomeJobsQuery(val since: Long = 0)
@Serializable data class HomeJobsReply(val now: Long, val jobs: List<HomeJob>)
@Serializable data class HomeCancel(val jobId: String)
@Serializable data class HomeCancelReply(val state: JobState)
@Serializable data class HomeLibraryQuery(val sinceVersion: Long = 0)
@Serializable data class HomeLibraryReply(val version: Long, val titles: List<JsonElement> = emptyList())
@Serializable data class HomeKey(val key: String)

/** The phone's disk settings for the TV, password included (the whole request is encrypted end to end). */
@Serializable
data class HomeConfig(
    val address: String,
    val share: String,
    val user: String,
    val password: String? = null,
    val smb1: Boolean = false,
    val retentionDays: Int = 7,
    val cacheShare: Int = 40,
    val retention: RetentionSettings? = null,
    /** The phone just proved it's at home with this TV (its HMAC proof on the LAN): the TV may run its first Test (I6). */
    val atHome: Boolean = false,
)

@Serializable data class HomeConfigReply(val ok: Boolean, val test: String? = null)

/** In the TV's hello (v3): the disk at a glance. */
@Serializable data class HomeSummary(val diskState: String, val freeBytes: Long? = null, val activeJobs: Int = 0)

object HomeRpc {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun <T> request(id: String, op: String, serializer: KSerializer<T>, body: T) = RpcRequest(id, op, json.encodeToJsonElement(serializer, body))
    fun <T> ok(id: String, serializer: KSerializer<T>, body: T) = RpcReply(id, true, json.encodeToJsonElement(serializer, body))
    fun error(id: String, message: String) = RpcReply(id, false, null, message)
    fun <T> bodyOf(r: RpcRequest, serializer: KSerializer<T>): T = json.decodeFromJsonElement(serializer, r.body)
    fun <T> bodyOf(r: RpcReply, serializer: KSerializer<T>): T? = r.body?.let { json.decodeFromJsonElement(serializer, it) }

    /** A TV that predates v3 can't take home requests: the phone shows "Update One Stream on the TV". */
    fun supported(hello: HelloResponse) = hello.protocol >= 3

    const val UPDATE_TV = "Update One Stream on the TV"
}

/** One request id the TV has seen, kept 30 days (`files/home/requests.json`). */
@Serializable data class SeenRequest(val jobId: String, val at: Long, val reply: RpcReply)

/** A job the TV keeps. [files] null = the whole torrent; otherwise the wanted file indexes (merged, never narrowed). */
@Serializable
data class TvJob(
    val jobId: String,
    val requestIds: List<String>,
    val key: String,
    val label: String,
    val magnet: String? = null,
    val torrentB64: String? = null,
    val files: Set<Int>? = null,
    val fileHints: List<String> = emptyList(),
    val origin: ManifestOrigin? = null,
    val dest: HomeDest = HomeDest.LIBRARY,
    val state: JobState,
    val createdAt: Long,
    val updatedAt: Long,
    val bytesOnDisk: Long = 0,
    val wantedBytes: Long = 0,
    val error: String? = null,
) {
    val active: Boolean get() = state !in setOf(JobState.DONE, JobState.FAILED, JobState.CANCELLED)
}

@Serializable
data class TvHomeState(val requests: Map<String, SeenRequest> = emptyMap(), val jobs: List<TvJob> = emptyList(), val nextJob: Long = 1)

/**
 * The TV's side of the home RPC, pure (docs/HOME_DISK.md §9.2). Until the conveyor exists (Phase 5) and a disk
 * is connected, every accepted job waits: "Accepted, waiting for home storage" (state WAITING_DISK).
 * `home.config` needs the Keystore and a Test, so the app handles it before calling [handle].
 */
object HomeAgent {
    const val KEEP_REQUESTS_MS = 30 * 86_400_000L
    const val WAITING_FOR_STORAGE = "Accepted, waiting for home storage"

    fun handle(state: TvHomeState, req: RpcRequest, now: Long, diskReady: Boolean = false): Pair<TvHomeState, RpcReply> {
        val pruned = state.copy(requests = state.requests.filterValues { now - it.at < KEEP_REQUESTS_MS })
        // A repeated id gets exactly the answer it got the first time (the phone may have missed it).
        pruned.requests[req.id]?.let { return pruned to it.reply }
        val (next, reply) = try {
            when (req.op) {
                HomeOps.DOWNLOAD -> download(pruned, req, now, diskReady)
                HomeOps.JOBS -> pruned to HomeRpc.ok(req.id, HomeJobsReply.serializer(), jobs(pruned, HomeRpc.bodyOf(req, HomeJobsQuery.serializer()).since, now))
                HomeOps.CANCEL -> cancel(pruned, req, now)
                HomeOps.LIBRARY -> pruned to HomeRpc.ok(req.id, HomeLibraryReply.serializer(), HomeLibraryReply(0))
                HomeOps.DELETE -> pruned to HomeRpc.error(req.id, "There's no home disk yet")
                HomeOps.STREAM -> pruned to HomeRpc.error(req.id, "Watching from the home disk comes later")
                HomeOps.LIBRARY_CHANGED -> pruned to RpcReply(req.id, true)
                else -> pruned to HomeRpc.error(req.id, "Unknown operation ${req.op.take(40)}")
            }
        } catch (e: Exception) {
            pruned to HomeRpc.error(req.id, "Bad request")
        }
        // Only changes are remembered for replay; queries are always answered fresh.
        val remember = req.op == HomeOps.DOWNLOAD || req.op == HomeOps.CANCEL
        val jobId = reply.body?.let { runCatching { HomeRpc.json.decodeFromJsonElement(HomeDownloadAck.serializer(), it).jobId }.getOrNull() }.orEmpty()
        return (if (remember) next.copy(requests = next.requests + (req.id to SeenRequest(jobId, now, reply))) else next) to reply
    }

    private fun download(s: TvHomeState, req: RpcRequest, now: Long, diskReady: Boolean): Pair<TvHomeState, RpcReply> {
        val d = HomeRpc.bodyOf(req, HomeDownload.serializer())
        val key = d.magnet?.let { Magnet.parse(it)?.infoHash?.lowercase() }
            ?: return s to HomeRpc.ok(req.id, HomeDownloadAck.serializer(), HomeDownloadAck("", AckState.REJECTED, "Can't read this link"))
        val label = d.label.ifBlank { d.origin?.label ?: Magnet.parse(d.magnet)?.name ?: key.take(8) }
        val existing = s.jobs.lastOrNull { it.key == key && it.state != JobState.CANCELLED && it.state != JobState.FAILED }
        if (existing != null) {
            // Same title asked again (another id): one job, wanted files merged, never narrowed.
            val merged = existing.copy(
                requestIds = existing.requestIds + req.id,
                files = when {
                    existing.files == null || d.fullTorrent -> null
                    d.fileIdx != null -> existing.files + d.fileIdx
                    else -> existing.files
                },
                fileHints = (existing.fileHints + listOfNotNull(d.fileHint)).distinct(),
                updatedAt = if (existing.state == JobState.DONE) existing.updatedAt else now,
            )
            val jobs = s.jobs.map { if (it.jobId == existing.jobId) merged else it }
            return s.copy(jobs = jobs) to HomeRpc.ok(req.id, HomeDownloadAck.serializer(), HomeDownloadAck(existing.jobId, AckState.DUPLICATE, null, existing.state))
        }
        val jobId = "j${s.nextJob}"
        val state = if (diskReady) JobState.QUEUED else JobState.WAITING_DISK
        val job = TvJob(
            jobId, listOf(req.id), key, label, d.magnet, d.torrentB64,
            files = if (d.fullTorrent || d.fileIdx == null) null else setOf(d.fileIdx),
            fileHints = listOfNotNull(d.fileHint), origin = d.origin, dest = d.dest, state = state, createdAt = now, updatedAt = now,
        )
        val ack = HomeDownloadAck(jobId, AckState.QUEUED, if (diskReady) null else WAITING_FOR_STORAGE, state)
        return s.copy(jobs = s.jobs + job, nextJob = s.nextJob + 1) to HomeRpc.ok(req.id, HomeDownloadAck.serializer(), ack)
    }

    private fun cancel(s: TvHomeState, req: RpcRequest, now: Long): Pair<TvHomeState, RpcReply> {
        val jobId = HomeRpc.bodyOf(req, HomeCancel.serializer()).jobId
        val job = s.jobs.firstOrNull { it.jobId == jobId } ?: return s to HomeRpc.error(req.id, "No such job")
        val after = if (job.active) job.copy(state = JobState.CANCELLED, updatedAt = now) else job
        return s.copy(jobs = s.jobs.map { if (it.jobId == jobId) after else it }) to HomeRpc.ok(req.id, HomeCancelReply.serializer(), HomeCancelReply(after.state))
    }

    /** Jobs changed after [since] (0 = all), with their place in the FIFO queue. */
    fun jobs(s: TvHomeState, since: Long, now: Long): HomeJobsReply {
        val queue = s.jobs.filter { it.active }.sortedBy { it.createdAt }
        val rows = s.jobs.filter { since <= 0 || it.updatedAt > since }.map { j ->
            HomeJob(
                j.jobId, j.requestIds.first(), j.key, j.label, j.state, j.bytesOnDisk, j.wantedBytes,
                queuePos = queue.indexOfFirst { it.jobId == j.jobId }.takeIf { it >= 0 }?.plus(1),
                error = j.error ?: if (j.state == JobState.WAITING_DISK) WAITING_FOR_STORAGE else null,
                updatedAt = j.updatedAt,
            )
        }
        return HomeJobsReply(now, rows)
    }

    fun summary(s: TvHomeState, diskState: String, freeBytes: Long?) = HomeSummary(diskState, freeBytes, s.jobs.count { it.active })
}
