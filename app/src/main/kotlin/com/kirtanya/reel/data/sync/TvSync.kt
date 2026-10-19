package com.kirtanya.reel.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.sync.SyncHost
import com.kirtanya.reel.core.sync.SyncProtocol
import com.kirtanya.reel.core.sync.SyncServer
import com.kirtanya.reel.data.SourcesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What the TV's Settings shows. */
data class TvSyncState(
    val running: Boolean = false,
    val code: String = "",
    val deviceName: String = "",
    val pairedPhones: List<Pair<String, String>> = emptyList(), // token → phone name
    val lastUpdate: String? = null,
    val lastUpdateAt: Long = 0,
)

/** TV side: runs the sync server while Reel is alive and announces it on the network. */
class TvSync(private val context: Context, private val sources: SourcesStore) : SyncHost {
    private val prefs = context.getSharedPreferences("sync_tv", Context.MODE_PRIVATE)
    private val json = Json
    private val tokenMap = MapSerializer(String.serializer(), String.serializer())
    private var server: SyncServer? = null
    private var stopAnnouncing: (() -> Unit)? = null

    private val _state = MutableStateFlow(TvSyncState())
    val state: StateFlow<TvSyncState> = _state.asStateFlow()

    override val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

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
        stopAnnouncing = Nsd(context).announce(deviceName, s.listeningPort)
        Log.i(TAG, "sync server on ${s.listeningPort}")
        refresh()
    }

    fun forgetPhone(token: String) {
        savePaired(pairedTokens() - token)
        refresh()
    }

    override fun currentBundle(): SourcesBundle = sources.exportBundle()
    override suspend fun apply(bundle: SourcesBundle): String = sources.applyBundle(bundle)
    override fun pairedTokens(): Map<String, String> =
        prefs.getString("tokens", null)?.let { runCatching { json.decodeFromString(tokenMap, it) }.getOrNull() }.orEmpty()

    override fun savePairedToken(token: String, device: String) = savePaired(pairedTokens() + (token to device))

    private fun savePaired(map: Map<String, String>) {
        prefs.edit().putString("tokens", json.encodeToString(tokenMap, map)).apply()
    }

    private fun refresh() {
        val s = server
        _state.value = TvSyncState(
            running = s != null,
            code = s?.code.orEmpty(),
            deviceName = deviceName,
            pairedPhones = pairedTokens().toList(),
            lastUpdate = prefs.getString("lastUpdate", null),
            lastUpdateAt = prefs.getLong("lastUpdateAt", 0),
        )
    }

    /** For the adb probe. */
    fun port(): Int = server?.listeningPort ?: -1

    companion object {
        private const val TAG = "ReelSync"
    }
}
