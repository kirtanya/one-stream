package com.kirtanya.reel.homedisk

import android.content.Context
import android.util.AtomicFile
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.HomeSettings
import com.kirtanya.reel.core.report.Redactor
import com.kirtanya.reel.core.sources.OkHttpFetcher
import com.kirtanya.reel.core.sync.SyncClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home disk feature's singletons (docs/HOME_DISK.md): settings, the Keystore secrets, the network watch,
 * the disk's connection manager, and per device the phone's request client + P2P gate or the TV's agent.
 * Phase 2: everything is set up and says "Not connected" until the disk exists.
 */
class Home(private val app: ReelApp) {
    private val prefs = app.getSharedPreferences("home_disk", Context.MODE_PRIVATE)
    val secrets = HomeSecrets.get(app)
    val network = NetworkWatch(app)
    val isTv: Boolean get() = app.isTv

    private val _settings = MutableStateFlow(HomeSettings.decode(prefs.getString("settings", null)))
    val settings: StateFlow<HomeSettings> = _settings.asStateFlow()

    private val _passwordSaved = MutableStateFlow(!secrets.get(HomeSecrets.SMB_PASSWORD).isNullOrEmpty())
    val passwordSaved: StateFlow<Boolean> = _passwordSaved.asStateFlow()

    /** Phone ↔ TV requests: short timeouts (a sleeping TV must not hold anything up). */
    val sync by lazy {
        val http = app.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        SyncClient(OkHttpFetcher(http, null))
    }

    val disk by lazy { HomeDisk(app, this) }
    val gate by lazy { P2pGate(app, this) }
    val client by lazy { HomeClient(app, this) }
    val agent by lazy { TvHomeAgent(app, this) }

    fun update(change: (HomeSettings) -> HomeSettings) {
        synchronized(this) {
            val next = change(_settings.value)
            if (next == _settings.value) return
            prefs.edit().putString("settings", HomeSettings.encode(next)).apply()
            _settings.value = next
        }
    }

    /** The share password, only for signing in and for `home.config` (never logged, never in reports). */
    fun password(): String? = secrets.get(HomeSecrets.SMB_PASSWORD)

    fun savePassword(password: String?) {
        secrets.put(HomeSecrets.SMB_PASSWORD, password?.takeIf { it.isNotEmpty() })
        _passwordSaved.value = !password.isNullOrEmpty()
    }

    /** Set up = address, folder, user and password all there. */
    val setUp: Boolean get() = settings.value.filledIn && passwordSaved.value

    /** Problem reports hide the password (lines dropped) and the user name and folder (masked), §6. */
    fun redactor(): Redactor {
        val s = settings.value
        return Redactor(passwords = secrets.all(), names = listOf(s.user, s.share))
    }

    fun start() {
        network.start()
        if (isTv) agent.start() else {
            gate.start()
            client.start()
        }
        disk.start()
    }

    companion object {
        /** `files/home/<name>`: small JSON state, written atomically. */
        fun file(context: Context, name: String) = AtomicFile(File(File(context.filesDir, "home").apply { mkdirs() }, name))

        fun read(f: AtomicFile): String? = runCatching { if (f.baseFile.exists()) f.readFully().decodeToString() else null }.getOrNull()

        fun write(f: AtomicFile, text: String) {
            val out = f.startWrite()
            try {
                out.write(text.toByteArray())
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
            }
        }
    }
}
