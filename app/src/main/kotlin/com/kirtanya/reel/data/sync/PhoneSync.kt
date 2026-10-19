package com.kirtanya.reel.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sync.SyncClient
import com.kirtanya.reel.core.sync.SyncException
import com.kirtanya.reel.data.SourcesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PairedTv(
    val serviceName: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val lastSyncAt: Long = 0,
    val lastSummary: String? = null,
    val lastError: String? = null,
    /** Hash of the sources last delivered, so unchanged sources aren't sent again. */
    val lastHash: Int = 0,
)

data class PhoneSyncState(val tvs: List<PairedTv> = emptyList(), val autoSync: Boolean = true, val syncing: Boolean = false)

/**
 * Phone side: pairs with TVs once (4-digit code), then sends this phone's addons and plugin repos
 * whenever they change, so the TV is set up without typing on it.
 */
@OptIn(FlowPreview::class)
class PhoneSync(
    private val context: Context,
    private val sources: SourcesStore,
    http: HttpFetcher,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("sync_phone", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(PairedTv.serializer())
    private val client = SyncClient(http)
    private val nsd = Nsd(context)
    private val pushLock = Mutex()

    private val _state = MutableStateFlow(PhoneSyncState(load(), prefs.getBoolean("auto", true)))
    val state: StateFlow<PhoneSyncState> = _state.asStateFlow()

    val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Keeps paired TVs up to date: on start, and a few seconds after every change. */
    fun start() {
        scope.launch {
            sources.ensureReady()
            if (_state.value.autoSync) pushAll()
            sources.state.drop(1).debounce(3_000).collect { if (_state.value.autoSync) pushAll() }
        }
    }

    suspend fun discover(): List<FoundTv> = nsd.discover()

    /** Pairs with [tv] using the code on its screen, then sends sources straight away. */
    suspend fun pair(tv: FoundTv, code: String): String {
        val paired = client.pair(tv.base, code, deviceName)
        save(_state.value.tvs.filterNot { it.serviceName == tv.serviceName } + PairedTv(tv.serviceName, paired.name, tv.host, tv.port, paired.token))
        return push(tv.serviceName, force = true) ?: "Paired with ${paired.name}"
    }

    fun forget(serviceName: String) = save(_state.value.tvs.filterNot { it.serviceName == serviceName })

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _state.value = _state.value.copy(autoSync = on)
        if (on) scope.launch { pushAll() }
    }

    /** Sends to every paired TV whose copy is out of date. */
    suspend fun pushAll(force: Boolean = false) {
        _state.value.tvs.forEach { push(it.serviceName, force) }
    }

    /**
     * Sends this phone's sources to one TV. Tries the last known address, then looks the TV up
     * again on the network (its address can change). Returns a message, or null if nothing to do.
     */
    suspend fun push(serviceName: String, force: Boolean = false): String? = pushLock.withLock {
        val tv = _state.value.tvs.firstOrNull { it.serviceName == serviceName } ?: return@withLock null
        val bundle = sources.exportBundle(from = deviceName)
        val hash = bundle.copy(from = "").hashCode()
        if (!force && hash == tv.lastHash && tv.lastError == null) return@withLock null
        _state.value = _state.value.copy(syncing = true)
        try {
            val summary = try {
                client.push("http://${tv.host}:${tv.port}", tv.token, bundle)
            } catch (e: SyncException) {
                if (e.message?.contains("Pair again") == true) throw e
                // Address changed? Find it again by name.
                val found = discover().firstOrNull { it.serviceName == tv.serviceName } ?: throw e
                update(tv.serviceName) { it.copy(host = found.host, port = found.port) }
                client.push(found.base, tv.token, bundle)
            }
            update(tv.serviceName) { it.copy(lastSyncAt = System.currentTimeMillis(), lastSummary = summary, lastError = null, lastHash = hash) }
            Log.i(TAG, "synced ${tv.name}: $summary")
            "${tv.name}: $summary"
        } catch (e: Exception) {
            update(tv.serviceName) { it.copy(lastError = e.message ?: "Couldn't reach the TV") }
            Log.w(TAG, "sync ${tv.name} failed: ${e.message}")
            e.message
        } finally {
            _state.value = _state.value.copy(syncing = false)
        }
    }

    private fun update(serviceName: String, change: (PairedTv) -> PairedTv) =
        save(_state.value.tvs.map { if (it.serviceName == serviceName) change(it) else it })

    private fun save(tvs: List<PairedTv>) {
        prefs.edit().putString("tvs", json.encodeToString(list, tvs)).apply()
        _state.value = _state.value.copy(tvs = tvs)
    }

    private fun load(): List<PairedTv> =
        prefs.getString("tvs", null)?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()

    companion object {
        private const val TAG = "ReelSync"
    }
}
