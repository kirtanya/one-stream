package com.kirtanya.reel.homedisk

import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.HomeNetwork
import com.kirtanya.reel.core.sync.HomeAgent
import com.kirtanya.reel.core.sync.HomeCancel
import com.kirtanya.reel.core.sync.HomeConfig
import com.kirtanya.reel.core.sync.HomeConfigReply
import com.kirtanya.reel.core.sync.HomeOps
import com.kirtanya.reel.core.sync.HomeRpc
import com.kirtanya.reel.core.sync.HomeSummary
import com.kirtanya.reel.core.sync.PairedPhone
import com.kirtanya.reel.core.sync.RpcReply
import com.kirtanya.reel.core.sync.RpcRequest
import com.kirtanya.reel.core.sync.TvHomeState
import com.kirtanya.reel.core.sync.TvJob
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The TV's side of the home RPC (docs/HOME_DISK.md §9): jobs and the request log in `files/home/tv_home.json`
 * (the idempotency map lives inside it), `home.config` into its own settings and Keystore. Until the conveyor
 * (Phase 5) and a disk exist, every request is "Accepted, waiting for home storage".
 */
class TvHomeAgent(private val app: ReelApp, private val home: Home) {
    private val file = Home.file(app, "tv_home.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val _state = MutableStateFlow(Home.read(file)?.let { runCatching { json.decodeFromString(TvHomeState.serializer(), it) }.getOrNull() } ?: TvHomeState())
    val state: StateFlow<TvHomeState> = _state.asStateFlow()

    fun start() = Unit

    suspend fun rpc(phone: PairedPhone, request: RpcRequest): RpcReply = lock.withLock {
        Log.i(TAG, "rpc ${request.op} from ${phone.name}")
        if (request.op == HomeOps.CONFIG) return@withLock config(request)
        val diskReady = false // the conveyor arrives in Phase 5; until then every job waits for home storage
        val (next, reply) = HomeAgent.handle(_state.value, request, System.currentTimeMillis(), diskReady)
        save(next)
        reply
    }

    /** The phone's disk settings (the password goes straight into this TV's Keystore), then the TV's own Test. */
    private suspend fun config(request: RpcRequest): RpcReply {
        val c = runCatching { HomeRpc.bodyOf(request, HomeConfig.serializer()) }.getOrNull() ?: return HomeRpc.error(request.id, "Bad settings")
        home.update { s ->
            s.copy(address = c.address, share = c.share, user = c.user, smb1 = c.smb1, retention = c.retention ?: s.retention.copy(days = c.retentionDays, cacheSharePct = c.cacheShare))
        }
        c.password?.let(home::savePassword)
        // The phone proved it's at home with this TV, so the TV's first Test may sign in (I6) and saves this network.
        val outcome = home.disk.test(confirmedHome = c.atHome)
        val line = when (outcome) {
            is TestOutcome.Done -> outcome.line
            is TestOutcome.Refused -> outcome.line
            TestOutcome.NeedsConfirm -> "Saved. Test it on the TV once you're both at home."
        }
        return HomeRpc.ok(request.id, HomeConfigReply.serializer(), HomeConfigReply(true, line))
    }

    /** The TV's own Settings: cancel an at-home download. */
    fun cancel(job: TvJob) {
        app.appScope.launch {
            rpcLocal(HomeRpc.request(UUID.randomUUID().toString(), HomeOps.CANCEL, HomeCancel.serializer(), HomeCancel(job.jobId)))
        }
    }

    /** Runs a request on the TV itself (Settings actions, the adb probe). */
    suspend fun rpcLocal(request: RpcRequest): RpcReply = rpc(PairedPhone("tv", "this TV", ""), request)

    fun summary(): HomeSummary {
        val d = home.disk.ui.value.state
        return HomeAgent.summary(_state.value, d::class.simpleName ?: "Unknown", (d as? HomeDiskState.Connected)?.freeBytes)
    }

    /** LAN IPv4 and, with Tailscale up, the 100.x address (§9.1). */
    fun addresses(): List<String> = listOfNotNull(home.network.lanAddress(), NetworkWatch.tailnetAddress())

    private fun save(s: TvHomeState) {
        if (s == _state.value) return
        _state.value = s
        Home.write(file, json.encodeToString(TvHomeState.serializer(), s))
    }

    companion object {
        private const val TAG = "ReelHome"
    }
}
