package com.kirtanya.reel.homedisk

import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.P2pRule
import com.kirtanya.reel.core.homedisk.P2pUse
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.homedisk.WaitReason
import com.kirtanya.reel.engine.TorrentEngine
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.engine.TorrentSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A question or refusal the network rule puts in front of the user (shown by NetworkPromptHost). */
data class GatePrompt(
    val verdict: P2pVerdict,
    val use: P2pUse,
    /** Go ahead (the user allowed this Wi-Fi, or chose Start now anyway). */
    val proceed: () -> Unit,
    /** "Download at home instead", when the thing has a torrent link the TV can take. */
    val atHome: (() -> Unit)? = null,
    /** "Download when I'm home": add it now, held until an allowed network. */
    val later: (() -> Unit)? = null,
)

/**
 * The phone's P2P network rule applied (docs/HOME_DISK.md §5.3): downloads on a network where they must wait
 * are paused (and new ones added paused, through [TorrentEngine.downloadHold]); they start again by
 * themselves on an allowed network. Plays ask or are refused through [prompt]. The TV is never gated.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class P2pGate(private val app: ReelApp, private val home: Home) {
    private val heldFile = Home.file(app, "held.json")
    private val codec = SetSerializer(String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** Downloads this gate paused (info-hash, lowercase), so only those are resumed. */
    private val _held = MutableStateFlow(Home.read(heldFile)?.let { runCatching { json.decodeFromString(codec, it) }.getOrNull() }.orEmpty())
    val held: StateFlow<Set<String>> = _held.asStateFlow()

    /** "Start now anyway" on mobile data, per download, until the network changes. */
    private val anyway = HashSet<String>()
    private var anywayNetwork: String? = null

    private val _prompt = MutableStateFlow<GatePrompt?>(null)
    val prompt: StateFlow<GatePrompt?> = _prompt.asStateFlow()

    /** Why downloads wait right now (null = they may run). */
    private val _waiting = MutableStateFlow<WaitReason?>(null)
    val waiting: StateFlow<WaitReason?> = _waiting.asStateFlow()

    fun verdict(use: P2pUse, hash: String? = null): P2pVerdict {
        val net = home.network.now.value
        val s = home.settings.value
        val isHome = home.disk.ui.value.home?.isHome == true ||
            (net.isWifiLike && net.fingerprint != null && net.fingerprint == s.homeFingerprint)
        return P2pRule.decide(use, net, isHome, s.known, s.mobileData, startAnyway = hash != null && hash.lowercase() in anyway, isTv = home.isTv)
    }

    fun start() {
        // New and restored downloads start paused where they must wait (no traffic at all before we look).
        TorrentEngine.downloadHold = { hash ->
            val hold = !P2pRule.downloadsAllowed(verdict(P2pUse.DOWNLOAD, hash))
            if (hold) setHeld(_held.value + hash.lowercase())
            hold
        }
        app.appScope.launch {
            val torrents = app.engineCreated.flatMapLatest { created -> if (created) app.engine.torrents else flowOf(emptyList()) }
            combine(home.network.now, home.settings, home.disk.ui, torrents) { net, _, _, list -> net to list }.collect { (net, list) ->
                if (net.fingerprint != anywayNetwork || net.transport.name != anywayTransport) {
                    anyway.clear()
                    anywayNetwork = net.fingerprint
                    anywayTransport = net.transport.name
                }
                apply(list)
            }
        }
    }

    private var anywayTransport: String? = null

    /** Pauses what may not run here, resumes what this gate paused once it may. */
    private fun apply(list: List<TorrentSnapshot>) {
        val engine = app.engineIfCreated ?: return
        _waiting.value = P2pRule.waitReason(verdict(P2pUse.DOWNLOAD))
        val held = _held.value.toMutableSet()
        val present = list.mapTo(HashSet()) { it.infoHash.lowercase() }
        held.retainAll(present.ifEmpty { held }) // a removed download leaves the set (keep all while the list is still empty)
        for (t in list) {
            if (t.mode != TorrentMode.DOWNLOAD) continue
            val key = t.infoHash.lowercase()
            val allowed = P2pRule.downloadsAllowed(verdict(P2pUse.DOWNLOAD, key))
            val moving = t.phase in MOVING
            when {
                // Paused here, even if someone pressed Resume: no BitTorrent on a network that says wait.
                !allowed && moving -> {
                    engine.pause(t.infoHash)
                    held += key
                    Log.i(TAG, "held ${key.take(8)}: ${P2pRule.waitReason(verdict(P2pUse.DOWNLOAD))}")
                }
                allowed && key in held -> {
                    if (t.phase == TorrentPhase.PAUSED) engine.resume(t.infoHash)
                    held -= key
                    Log.i(TAG, "resumed ${key.take(8)}")
                }
            }
        }
        setHeld(held)
    }

    private fun setHeld(next: Set<String>) {
        if (next == _held.value) return
        _held.value = next
        Home.write(heldFile, json.encodeToString(codec, next))
    }

    /** "Start now anyway" (mobile data only; never on a work network, the rule refuses it). */
    fun startAnyway(hash: String) {
        anyway += hash.lowercase()
        app.engineIfCreated?.torrents?.value?.let(::apply)
    }

    /** The answer for the current Wi-Fi, remembered by fingerprint. */
    fun answer(allowed: Boolean) {
        val fp = home.network.now.value.fingerprint ?: return
        val label = "Wi-Fi · " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
        home.update { it.answer(fp, label, allowed, System.currentTimeMillis()) }
        app.engineIfCreated?.torrents?.value?.let(::apply)
    }

    /**
     * Play from a torrent: runs [play] when the rule allows it here, otherwise asks (unknown Wi-Fi) or refuses
     * (work Wi-Fi), offering [atHome] when the source can be downloaded at home instead.
     */
    fun play(play: () -> Unit, atHome: (() -> Unit)? = null) {
        when (val v = verdict(P2pUse.STREAM)) {
            P2pVerdict.Allow -> play()
            else -> _prompt.value = GatePrompt(v, P2pUse.STREAM, proceed = play, atHome = atHome)
        }
    }

    /** A download the user asked for on this phone: added now when allowed, else after the question. */
    fun download(hash: String?, add: () -> Unit, atHome: (() -> Unit)?) {
        when (val v = verdict(P2pUse.DOWNLOAD, hash)) {
            P2pVerdict.Allow -> add()
            else -> _prompt.value = GatePrompt(v, P2pUse.DOWNLOAD, proceed = { hash?.let { h -> if (v is P2pVerdict.Wait && v.canStartAnyway) anyway += h.lowercase() }; add() }, atHome = atHome, later = add)
        }
    }

    fun dismiss() {
        _prompt.value = null
    }

    companion object {
        private const val TAG = "ReelHome"
        /** Phases that move BitTorrent traffic. */
        private val MOVING = setOf(TorrentPhase.METADATA, TorrentPhase.CHECKING, TorrentPhase.DOWNLOADING, TorrentPhase.SEEDING)
    }
}
