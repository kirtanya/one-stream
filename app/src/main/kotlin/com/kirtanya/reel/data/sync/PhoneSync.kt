package com.kirtanya.reel.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sync.SyncClient
import com.kirtanya.reel.core.sync.SyncException
import com.kirtanya.reel.core.sync.TvCredentials
import com.kirtanya.reel.data.SourcesStore
import com.kirtanya.reel.data.WatchlistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PairedTv(
    /** The TV's own id (checked on every push, so a look-alike never gets anything). */
    val tvId: String,
    val name: String,
    val host: String,
    val port: Int,
    val phoneId: String,
    val secret: String,
    val lastSyncAt: Long = 0,
    val lastSummary: String? = null,
    val lastError: String? = null,
    /** The TV no longer knows this phone: needs pairing again. */
    val needsPairing: Boolean = false,
    /** Hash of the sources last fully delivered, so unchanged sources aren't sent again. */
    val lastHash: Int = 0,
) {
    val credentials get() = TvCredentials(tvId, phoneId, secret)
}

data class PhoneSyncState(val tvs: List<PairedTv> = emptyList(), val autoSync: Boolean = true, val syncing: Boolean = false)

/**
 * Phone side: pairs with TVs once, then sends this phone's addons and plugin repos whenever they
 * change (and retries every couple of minutes while a TV is unreachable), so the TV is set up
 * without typing on it.
 */
@OptIn(FlowPreview::class)
class PhoneSync(
    private val context: Context,
    private val sources: SourcesStore,
    private val watchlist: WatchlistStore,
    http: HttpFetcher,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("sync_phone", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(PairedTv.serializer())
    private val client = SyncClient(http)
    private val nsd = Nsd(context)
    private val pushLock = Mutex()
    /** Pairing secrets live in the Keystore-backed store, not in preferences (docs/HOME_DISK.md §6). */
    private val secrets = com.kirtanya.reel.homedisk.HomeSecrets.get(context)

    private val _state = MutableStateFlow(PhoneSyncState(load(), prefs.getBoolean("auto", true)))
    val state: StateFlow<PhoneSyncState> = _state.asStateFlow()

    val deviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun start() {
        scope.launch {
            sources.ensureReady()
            // Every change to sources or the watchlist (and the current state once) → a push a few seconds
            // later; an unchanged bundle is skipped by hash.
            merge(sources.state.map { }, watchlist.all.map { }).debounce(3_000).collect { if (_state.value.autoSync) pushAll() }
        }
        scope.launch {
            while (isActive) {
                delay(2 * 60_000)
                if (_state.value.autoSync && _state.value.tvs.any { it.lastError != null && !it.needsPairing }) pushAll()
            }
        }
    }

    suspend fun discover(): List<FoundTv> = nsd.discover()

    /** Pairs with [tv] using the code on its screen, then sends sources straight away. */
    suspend fun pair(tv: FoundTv, code: String): String {
        val p = client.pair(tv.base, code, deviceName)
        val paired = PairedTv(p.tvId, p.name, tv.host, tv.port, p.phoneId, p.secret)
        save(_state.value.tvs.filterNot { it.tvId == p.tvId } + paired)
        return push(p.tvId, force = true) ?: "Paired with ${p.name}"
    }

    fun forget(tvId: String) {
        save(_state.value.tvs.filterNot { it.tvId == tvId })
        secrets.put(com.kirtanya.reel.homedisk.HomeSecrets.pairTv(tvId), null)
    }

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _state.value = _state.value.copy(autoSync = on)
        if (on) scope.launch { pushAll() }
    }

    /** Sends to every paired TV whose copy is out of date. */
    suspend fun pushAll(force: Boolean = false) {
        _state.value.tvs.forEach { push(it.tvId, force) }
    }

    /**
     * Sends this phone's sources to one TV: at its last address, else wherever it is now on the
     * network (found by its id). Returns a message, or null if there was nothing to send.
     */
    suspend fun push(tvId: String, force: Boolean = false): String? = pushLock.withLock {
        val tv = _state.value.tvs.firstOrNull { it.tvId == tvId } ?: return@withLock null
        if (tv.needsPairing && !force) return@withLock null
        val bundle = sources.exportBundle(from = deviceName).copy(watchlist = watchlist.all.value)
        val hash = bundle.copy(from = "").hashCode()
        if (!force && hash == tv.lastHash && tv.lastError == null) return@withLock null
        _state.value = _state.value.copy(syncing = true)
        try {
            val result = try {
                client.push("http://${tv.host}:${tv.port}", tv.credentials, bundle)
            } catch (e: SyncException) {
                if (e.needsPairing) throw e
                // Address changed (or someone else is there now)? Find the TV again by its id.
                val found = discover().firstOrNull { it.tvId == tv.tvId } ?: throw e
                update(tv.tvId) { it.copy(host = found.host, port = found.port) }
                client.push(found.base, tv.credentials, bundle)
            }
            val failed = result.failed > 0
            update(tv.tvId) {
                it.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastSummary = result.summary,
                    // Partly failed: keep the old hash so the next round tries again.
                    lastError = if (failed) "Some sources couldn't be reached by the TV; will retry" else null,
                    needsPairing = false,
                    lastHash = if (failed) it.lastHash else hash,
                )
            }
            Log.i(TAG, "synced ${tv.name}: ${result.summary}")
            "${tv.name}: ${result.summary}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val pair = (e as? SyncException)?.needsPairing == true
            update(tv.tvId) { it.copy(lastError = e.message ?: "Couldn't reach the TV", needsPairing = pair) }
            Log.w(TAG, "sync ${tv.name} failed: ${e.message}")
            e.message
        } finally {
            _state.value = _state.value.copy(syncing = false)
        }
    }

    private fun update(tvId: String, change: (PairedTv) -> PairedTv) =
        save(_state.value.tvs.map { if (it.tvId == tvId) change(it) else it })

    private fun save(tvs: List<PairedTv>) {
        // Secrets first (and only blanked in preferences once they are safely stored), so a failure never loses a pairing.
        val stored = secrets.available && secrets.put(tvs.associate { com.kirtanya.reel.homedisk.HomeSecrets.pairTv(it.tvId) to it.secret })
        val plain = if (stored) tvs.map { it.copy(secret = "") } else tvs
        prefs.edit().putString("tvs", json.encodeToString(list, plain)).apply()
        _state.value = _state.value.copy(tvs = tvs)
    }

    private fun load(): List<PairedTv> {
        val saved = prefs.getString("tvs", null)?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()
        val tvs = saved.map { tv -> tv.copy(secret = tv.secret.ifEmpty { secrets.get(com.kirtanya.reel.homedisk.HomeSecrets.pairTv(tv.tvId)).orEmpty() }) }
        // Older versions kept the secrets in plain preferences: move them once.
        if (saved.any { it.secret.isNotEmpty() } && secrets.available) {
            if (secrets.put(tvs.associate { com.kirtanya.reel.homedisk.HomeSecrets.pairTv(it.tvId) to it.secret })) {
                prefs.edit().putString("tvs", json.encodeToString(list, tvs.map { it.copy(secret = "") })).apply()
            }
        }
        return tvs
    }

    companion object {
        private const val TAG = "ReelSync"
    }
}
