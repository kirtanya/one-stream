package com.kirtanya.reel.homedisk

import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.ConnectPolicy
import com.kirtanya.reel.core.homedisk.DiskTest
import com.kirtanya.reel.core.homedisk.DiskTestFacts
import com.kirtanya.reel.core.homedisk.BigFacts
import com.kirtanya.reel.core.homedisk.HomeBackend
import com.kirtanya.reel.core.homedisk.HomeDecision
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.HomeNetwork
import com.kirtanya.reel.core.homedisk.HomeReason
import com.kirtanya.reel.core.homedisk.NetworkFingerprint
import com.kirtanya.reel.core.homedisk.NetworkNow
import com.kirtanya.reel.core.homedisk.NotConnectedReason
import com.kirtanya.reel.core.homedisk.UsbTest
import com.kirtanya.reel.core.homedisk.UsbTestFacts
import com.kirtanya.reel.engine.EngineStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What Settings → Home storage shows about the disk. */
data class DiskUi(
    val state: HomeDiskState = HomeDiskState.NotSetUp,
    /** The last Test's line and when it ran. */
    val lastTest: String? = null,
    val lastTestAt: Long = 0,
    val testing: Boolean = false,
    val home: HomeDecision? = null,
    /** The paired TV answered recently (phone): "At home" can start right away. */
    val tvReachable: Boolean = false,
)

/** What a Test press led to. */
sealed interface TestOutcome {
    /** No home network saved yet: the user must confirm they're at home first (I6). */
    data object NeedsConfirm : TestOutcome
    data class Refused(val line: String) : TestOutcome
    data class Done(val line: String, val facts: DiskTestFacts?) : TestOutcome
}

/**
 * The connection manager (docs/HOME_DISK.md §4.5, §5.2): home detection, the health check and the Test.
 * Invariant I6 is enforced here: no SMB sign-in unless this device is home (or the user confirmed it is, for
 * the very first Test); the phone over mobile data or another Wi-Fi never talks SMB.
 */
class HomeDisk(private val app: ReelApp, private val home: Home) {
    private val _ui = MutableStateFlow(DiskUi(lastTest = prefs().getString("lastTest", null), lastTestAt = prefs().getLong("lastTestAt", 0)))
    val ui: StateFlow<DiskUi> = _ui.asStateFlow()
    private val policy = ConnectPolicy()
    private var lastAttemptAt: Long? = null
    private val lock = Mutex()
    /** TV proof per network fingerprint, with when it was checked (cached for the life of the network). */
    private val proofs = HashMap<String, Pair<Boolean, Long>>()

    private fun prefs() = app.getSharedPreferences("home_disk", android.content.Context.MODE_PRIVATE)

    fun start() {
        app.appScope.launch {
            home.network.now.map { it.fingerprint to it.transport }.distinctUntilChanged().collect { refresh(deep = false) }
        }
        app.appScope.launch {
            home.settings.map { listOf(it.backend.name, it.address, it.share, it.user, it.smb1.toString()) }.distinctUntilChanged().collect {
                policy.reset()
                refresh(deep = false)
            }
        }
        if (home.isTv) {
            // A plugged-in or pulled-out disk shows up at once; the slow loop covers TVs that don't report it (§23.2).
            home.usb.watch { app.appScope.launch { if (usbChosen()) refresh(deep = false) } }
            app.appScope.launch {
                while (true) {
                    delay(USB_RECHECK_MS)
                    if (usbChosen()) refresh(deep = false)
                }
            }
        }
    }

    /** Is this device home right now? The phone asks the paired TV for its proof when needed ([deep] also searches with NSD). */
    suspend fun decideHome(deep: Boolean): HomeDecision {
        val net = home.network.refresh()
        val saved = home.settings.value.homeFingerprint
        val proof = if (home.isTv || !net.isWifiLike || net.fingerprint == null || net.fingerprint == saved) false else tvProof(net, deep)
        val d = HomeNetwork.decide(net, saved, proof)
        d.saveFingerprint?.let { fp -> home.update { it.copy(homeFingerprint = fp, homeSavedAt = System.currentTimeMillis()) } }
        if (d.reason == HomeReason.FINGERPRINT && !home.isTv && !_ui.value.tvReachable) tvProof(net, deep = false)
        _ui.value = _ui.value.copy(home = d)
        return d
    }

    /** The paired TV answers its HMAC challenge at a LAN address on this network: nobody else can (§5.2). */
    private suspend fun tvProof(net: NetworkNow, deep: Boolean): Boolean {
        val fp = net.fingerprint ?: return false
        proofs[fp]?.let { (ok, at) -> if (ok || System.currentTimeMillis() - at < 60_000) return ok }
        val tvs = app.phoneSync.state.value.tvs.filter { !it.needsPairing && it.secret.isNotEmpty() }
        var ok = false
        for (tv in tvs) {
            if (!NetworkFingerprint.isPrivateLan(tv.host)) continue
            ok = withTimeoutOrNull(5_000) { runCatching { home.sync.verifiedHello("http://${tv.host}:${tv.port}", tv.credentials) }.isSuccess } == true
            if (ok) break
        }
        if (!ok && deep && tvs.isNotEmpty()) {
            val found = runCatching { app.phoneSync.discover() }.getOrDefault(emptyList())
            for (f in found) {
                val tv = tvs.firstOrNull { it.tvId == f.tvId } ?: continue
                if (!NetworkFingerprint.isPrivateLan(f.host)) continue
                ok = withTimeoutOrNull(5_000) { runCatching { home.sync.verifiedHello(f.base, tv.credentials) }.isSuccess } == true
                if (ok) break
            }
        }
        proofs[fp] = ok to System.currentTimeMillis()
        _ui.value = _ui.value.copy(tvReachable = ok)
        return ok
    }

    /**
     * Where the disk stands now, with a light health check at home (sign in + free space, within the
     * retry policy). Called on network changes, settings changes and when Settings opens.
     */
    suspend fun refresh(deep: Boolean) = lock.withLock {
        // Home is decided even without a disk: the phone's P2P rule needs it (§5.3).
        val d = decideHome(deep)
        if (!home.setUp) {
            _ui.value = _ui.value.copy(state = HomeDiskState.NotSetUp)
            return@withLock
        }
        if (usbChosen()) {
            _ui.value = _ui.value.copy(state = usbState())
            return@withLock
        }
        val saved = home.settings.value.homeFingerprint
        val state = when {
            d.isHome -> health()
            home.isTv && saved == null -> HomeDiskState.NotConnected(NotConnectedReason.NOT_TESTED)
            home.isTv -> HomeDiskState.NotConnected(NotConnectedReason.NEW_NETWORK)
            d.reason == HomeReason.NO_HOME_SAVED -> HomeDiskState.NotConnected(NotConnectedReason.NOT_TESTED)
            else -> HomeDiskState.Away
        }
        _ui.value = _ui.value.copy(state = state)
    }

    private fun usbChosen() = home.settings.value.backend == HomeBackend.USB

    /** The phone never sees the drive: it is in the TV (§23.7). The TV looks at its volumes; nothing is signed into, so I6 doesn't apply. */
    private suspend fun usbState(): HomeDiskState {
        if (!home.isTv) return HomeDiskState.OnTv
        val found = withContext(Dispatchers.IO) { home.usb.find() }
        val state = found.state
        // The first disk that works is remembered, so a second one plugged in later doesn't take over (§23.2).
        if (state is HomeDiskState.Connected && found.volumeId != null && found.volumeId != home.settings.value.usbVolume) {
            home.update { it.copy(usbVolume = found.volumeId) }
        }
        // One stats line per change, not one per refresh (free space moves all the time).
        if (usbKind(state) != usbKind(_ui.value.state)) usbConnectStats(state)
        return state
    }

    private fun usbKind(s: HomeDiskState) = if (s is HomeDiskState.NotConnected) "no-${s.reason}" else s::class.simpleName

    private suspend fun health(): HomeDiskState {
        val s = home.settings.value
        val now = System.currentTimeMillis()
        val last = _ui.value.state
        if (policy.isStopped) return HomeDiskState.NotConnected(NotConnectedReason.PASSWORD_REJECTED)
        if (!policy.mayTry(now, lastAttemptAt)) return if (last is HomeDiskState.Connected || last is HomeDiskState.NotConnected) last else HomeDiskState.Checking
        val reachable = withContext(Dispatchers.IO) { SmbTest.reachable(s.address) }
        if (!reachable) return HomeDiskState.NotConnected(NotConnectedReason.NO_ANSWER).also { policy.recordResult(it) }
        // Something answers on the SMB port: a real sign-in (counted against the 3-an-hour limit).
        val password = home.password() ?: return HomeDiskState.NotSetUp
        policy.recordAttempt(now)
        lastAttemptAt = now
        val started = EngineStats.now()
        val facts = withContext(Dispatchers.IO) { runCatching { SmbTest.signInOnly(SmbLogin(s.address, s.share, s.user, password, s.smb1)) }.getOrNull() }
        val state = facts?.let(DiskTest::state) ?: HomeDiskState.NotConnected(NotConnectedReason.ERROR)
        policy.recordResult(state)
        stats("hd-connect", facts, EngineStats.now() - started)
        return state
    }

    /**
     * The Test button (§4.5). [confirmedHome]: the user said "I'm at home" (only asked when no home network is
     * saved yet). A successful Test saves this network as home and writes disk.json.
     */
    suspend fun test(confirmedHome: Boolean): TestOutcome = lock.withLock {
        if (usbChosen()) return@withLock testUsb()
        val s = home.settings.value
        val password = home.password()
        if (!s.filledIn || password.isNullOrEmpty()) return@withLock TestOutcome.Refused("Fill in the address, shared folder, user and password first.")
        val d = decideHome(deep = true)
        val markedWork = home.network.now.value.fingerprint?.let { it in s.known.work } == true
        if (!HomeNetwork.maySignIn(d, confirmedHome, markedWork)) {
            if (d.reason == HomeReason.NO_HOME_SAVED && !markedWork) return@withLock TestOutcome.NeedsConfirm
            val line = if (home.isTv) "This isn't the home network the disk was set up on. New router? Test again once it's set up."
            else "You're not on your home Wi-Fi. One Stream only signs in to the disk at home."
            return@withLock TestOutcome.Refused(line)
        }
        _ui.value = _ui.value.copy(testing = true)
        val started = EngineStats.now()
        policy.reset()
        val facts = withContext(Dispatchers.IO) {
            runCatching { SmbTest.run(SmbLogin(s.address, s.share, s.user, password, s.smb1)) }
                .getOrElse { DiskTestFacts(s.address, s.share, reachable = true, signIn = DiskTestFacts.SignIn.FAILED, error = it.javaClass.simpleName) }
        }
        val line = DiskTest.line(facts)
        val state = DiskTest.state(facts)
        policy.recordResult(state)
        if (DiskTest.succeeded(facts)) {
            val fp = home.network.now.value.fingerprint
            home.update { it.copy(smb1 = facts.smb1, homeFingerprint = fp ?: it.homeFingerprint, homeSavedAt = System.currentTimeMillis()) }
        }
        stats("hd-test", facts, EngineStats.now() - started)
        val now = System.currentTimeMillis()
        prefs().edit().putString("lastTest", line).putLong("lastTestAt", now).apply()
        _ui.value = _ui.value.copy(testing = false, lastTest = line, lastTestAt = now, state = state)
        Log.i(TAG, "test: ${facts.signIn ?: "unreachable"} ${facts.protocol ?: ""}")
        TestOutcome.Done(line, facts)
    }

    /**
     * The Test on the USB backend (§23.10): write 8 MiB and read it back, rename, and a split round-trip over three
     * small pieces. Only the TV has the drive. No sign-in, so no home check.
     */
    private suspend fun testUsb(): TestOutcome {
        if (!home.isTv) return TestOutcome.Refused("The USB disk is in your TV. Test it on the TV: Settings → Home storage.")
        _ui.value = _ui.value.copy(testing = true)
        val started = EngineStats.now()
        val found = withContext(Dispatchers.IO) { home.usb.find() }
        val facts = found.root?.let { root -> withContext(Dispatchers.IO) { UsbTest.run(root) } }
        val line = facts?.let(UsbTest::line) ?: (DiskTest.status(found.state, "") + hintFor(found.state))
        val ok = facts != null && UsbTest.succeeded(facts)
        if (ok && found.volumeId != null) home.update { it.copy(usbVolume = found.volumeId) }
        usbTestStats(found.state, facts, EngineStats.now() - started)
        val now = System.currentTimeMillis()
        prefs().edit().putString("lastTest", line).putLong("lastTestAt", now).apply()
        _ui.value = _ui.value.copy(testing = false, lastTest = line, lastTestAt = now, state = found.state)
        Log.i(TAG, "test usb: ok=$ok state=${found.state::class.simpleName}")
        return if (facts == null) TestOutcome.Refused(line) else TestOutcome.Done(line, null)
    }

    private fun hintFor(s: HomeDiskState) =
        if (s is HomeDiskState.NotConnected && s.reason == NotConnectedReason.NO_USB) ". Plug a drive into the TV, then test again." else ""

    /** The opt-in real-boundary check (adb probe only): a file past the first 4095 MiB piece, written and read across it. */
    suspend fun testUsbBig(): BigFacts? {
        val root = withContext(Dispatchers.IO) { home.usb.find().root } ?: return null
        return withContext(Dispatchers.IO) { UsbTest.big(root) }
    }

    /** "Forget home network": the next Test asks again whether you're at home. */
    fun forgetHome() {
        home.update { it.copy(homeFingerprint = null, homeSavedAt = 0) }
        proofs.clear()
        app.appScope.launch { refresh(deep = false) }
    }

    /** ReelStats lines (§16); never with names, paths or the password. */
    private fun stats(event: String, f: DiskTestFacts?, ms: Long) {
        val dev = if (home.isTv) "tv" else "phone"
        when (event) {
            "hd-test" -> EngineStats.log("hd-test", linkedMapOf(
                "dev" to dev, "ok" to if (f != null && DiskTest.succeeded(f)) 1 else 0, "reachable" to if (f?.reachable == true) 1 else 0,
                "writeMBps" to f?.writeMBps?.let { "%.1f".format(it) }, "readMBps" to f?.readMBps?.let { "%.1f".format(it) },
                "bigFile" to f?.bigFiles?.let { if (it) 1 else 0 }, "proto" to f?.protocol?.replace(" ", "")?.lowercase(), "ms" to ms,
            ))
            else -> EngineStats.log("hd-connect", linkedMapOf(
                "dev" to dev, "home" to 1, "proto" to f?.protocol?.replace(" ", "")?.lowercase(), "ms" to ms,
                "ok" to if (f?.signIn == DiskTestFacts.SignIn.OK) 1 else 0, "err" to f?.signIn?.takeIf { it != DiskTestFacts.SignIn.OK }?.name,
            ))
        }
    }

    private fun usbConnectStats(state: HomeDiskState) {
        val err = when ((state as? HomeDiskState.NotConnected)?.reason) {
            null -> null
            NotConnectedReason.NO_USB -> "NoUsb"
            NotConnectedReason.UNREADABLE -> "Unreadable"
            NotConnectedReason.FULL -> "Full"
            else -> "Other"
        }
        EngineStats.log("hd-connect", linkedMapOf("dev" to "tv", "src" to "usb", "ok" to if (state is HomeDiskState.Connected) 1 else 0, "err" to err))
    }

    private fun usbTestStats(state: HomeDiskState, f: UsbTestFacts?, ms: Long) {
        EngineStats.log("hd-test", linkedMapOf(
            "dev" to "tv", "src" to "usb", "ok" to if (f != null && UsbTest.succeeded(f)) 1 else 0, "state" to state::class.simpleName,
            "writeMBps" to f?.writeMBps?.let { "%.1f".format(it) }, "readMBps" to f?.readMBps?.let { "%.1f".format(it) },
            "splitOk" to f?.splitOk?.let { if (it) 1 else 0 }, "ms" to ms,
        ))
    }

    companion object {
        private const val TAG = "ReelHome"
        private const val USB_RECHECK_MS = 15 * 60_000L
    }
}
