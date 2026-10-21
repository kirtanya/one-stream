package com.kirtanya.reel.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kirtanya.reel.core.sync.ApplyResult
import com.kirtanya.reel.core.sync.PairedPhone
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.sync.SyncCrypto
import com.kirtanya.reel.core.sync.SyncHost
import com.kirtanya.reel.core.sync.SyncProtocol
import com.kirtanya.reel.core.sync.SyncServer
import com.kirtanya.reel.data.SourcesStore
import com.kirtanya.reel.data.WatchlistStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the TV's Settings shows. */
data class TvSyncState(
    val running: Boolean = false,
    /** Shown while pairing is open (the Phone & TV card is on screen), else empty. */
    val code: String = "",
    val pairingUntil: Long = 0,
    val deviceName: String = "",
    val pairedPhones: List<PairedPhone> = emptyList(),
    val lastUpdate: String? = null,
    val lastUpdateAt: Long = 0,
)

/**
 * TV side: runs the sync server while Reel's process is alive (kept up by the engine's foreground
 * service) and announces it on the network. Pairing only opens while the Phone & TV card is shown.
 */
class TvSync(private val context: Context, private val sources: SourcesStore, private val watchlist: WatchlistStore) : SyncHost {
    private val prefs = context.getSharedPreferences("sync_tv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val phones = ListSerializer(PairedPhone.serializer())
    private val applyLock = Mutex()
    /** Pairing secrets live in the Keystore-backed store, not in preferences (docs/HOME_DISK.md §6). */
    private val secrets = com.kirtanya.reel.homedisk.HomeSecrets.get(context)
    private var server: SyncServer? = null
    private var stopAnnouncing: (() -> Unit)? = null

    private val _state = MutableStateFlow(TvSyncState())
    val state: StateFlow<TvSyncState> = _state.asStateFlow()

    override val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override val tvId: String = prefs.getString("tvId", null) ?: SyncCrypto.randomHex(8).also { prefs.edit().putString("tvId", it).apply() }

    @Synchronized
    fun start() {
        if (server != null) return
        val s = runCatching { SyncServer(this, SyncProtocol.DEFAULT_PORT).apply { start(10_000, true) } }
            .recoverCatching { SyncServer(this, 0).apply { start(10_000, true) } } // default port taken
            .onFailure { Log.w(TAG, "sync server failed", it) }
            .getOrNull() ?: return
        s.onEvent = { event ->
            if (event.startsWith("synced:")) {
                val (_, device, summary) = event.split(":", limit = 3)
                prefs.edit().putString("lastUpdate", "$device · $summary").putLong("lastUpdateAt", System.currentTimeMillis()).apply()
            }
            refresh()
        }
        server = s
        stopAnnouncing = Nsd(context).announce(deviceName, s.listeningPort, tvId)
        Log.i(TAG, "sync server on ${s.listeningPort}")
        refresh()
    }

    /** The Phone & TV card is on screen: show a fresh code for a few minutes. */
    fun openPairing() {
        start()
        server?.openPairing()
        refresh()
    }

    fun closePairing() {
        server?.closePairing()
        refresh()
    }

    fun forgetPhone(id: String) {
        savePhones(pairedPhones().filterNot { it.id == id })
        secrets.put(com.kirtanya.reel.homedisk.HomeSecrets.pairPhone(id), null)
        refresh()
    }

    /** One apply at a time (a phone retry can't run alongside its first attempt). */
    override suspend fun apply(bundle: SourcesBundle): ApplyResult = applyLock.withLock {
        val result = sources.applyBundle(bundle)
        // The watchlist is merged, not mirrored: titles saved on the TV stay.
        val changed = bundle.watchlist?.let(watchlist::mergeFrom) ?: 0
        if (changed == 0) result
        else result.copy(summary = listOf(result.summary.takeUnless { it == "already up to date" }, "watchlist: $changed changed").filterNotNull().joinToString(", "))
    }

    override fun pairedPhones(): List<PairedPhone> {
        val saved = prefs.getString("phones", null)?.let { runCatching { json.decodeFromString(phones, it) }.getOrNull() }.orEmpty()
        val list = saved.map { p -> p.copy(secret = p.secret.ifEmpty { secrets.get(com.kirtanya.reel.homedisk.HomeSecrets.pairPhone(p.id)).orEmpty() }) }
        // Older versions kept the secrets in plain preferences: move them once.
        if (saved.any { it.secret.isNotEmpty() } && secrets.available) savePhones(list)
        return list.filter { it.secret.isNotEmpty() }
    }

    override fun savePairedPhone(phone: PairedPhone) = savePhones(pairedPhones().filterNot { it.name == phone.name } + phone)

    private fun savePhones(list: List<PairedPhone>) {
        // Secrets first (and only blanked in preferences once they are safely stored), so a failure never loses a pairing.
        val stored = secrets.available && secrets.put(list.associate { com.kirtanya.reel.homedisk.HomeSecrets.pairPhone(it.id) to it.secret })
        prefs.edit().putString("phones", json.encodeToString(phones, if (stored) list.map { it.copy(secret = "") } else list)).apply()
    }

    private fun refresh() {
        val s = server
        _state.value = TvSyncState(
            running = s != null,
            code = if (s?.pairingOpen == true) s.code else "",
            pairingUntil = if (s?.pairingOpen == true) System.currentTimeMillis() + SyncProtocol.PAIRING_WINDOW_MS else 0,
            deviceName = deviceName,
            pairedPhones = pairedPhones(),
            lastUpdate = prefs.getString("lastUpdate", null),
            lastUpdateAt = prefs.getLong("lastUpdateAt", 0),
        )
    }

    // Home disk (sync v3, docs/HOME_DISK.md §9): requests from paired phones, addresses and the disk in the hello.
    override suspend fun rpc(phone: PairedPhone, request: com.kirtanya.reel.core.sync.RpcRequest) =
        com.kirtanya.reel.ReelApp.get(context).home.agent.rpc(phone, request)

    override fun addresses(): List<String> = com.kirtanya.reel.ReelApp.get(context).home.agent.addresses()

    override fun homeSummary() = com.kirtanya.reel.ReelApp.get(context).home.agent.summary()

    /** For the adb probe. */
    fun port(): Int = server?.listeningPort ?: -1

    companion object {
        private const val TAG = "ReelSync"
    }
}
