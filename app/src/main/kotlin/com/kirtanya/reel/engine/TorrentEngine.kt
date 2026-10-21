package com.kirtanya.reel.engine

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.AtomicFile
import android.util.Log
import com.kirtanya.reel.core.magnet.DEFAULT_TRACKERS
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.stream.CacheEntry
import com.kirtanya.reel.core.stream.CachePolicy
import com.kirtanya.reel.core.stream.ModeChange
import com.kirtanya.reel.core.stream.ModeFacts
import com.kirtanya.reel.core.stream.ModeStep
import com.kirtanya.reel.core.stream.DiskBackend
import com.kirtanya.reel.core.stream.FileChoice
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.AppliedState
import com.kirtanya.reel.core.stream.ContainerProbe
import com.kirtanya.reel.core.stream.DiffContext
import com.kirtanya.reel.core.stream.GuardState
import com.kirtanya.reel.core.stream.KiB
import com.kirtanya.reel.core.stream.MediaRate
import com.kirtanya.reel.core.stream.PlanDiff
import com.kirtanya.reel.core.stream.PlanInput
import com.kirtanya.reel.core.stream.PlanMerge
import com.kirtanya.reel.core.stream.PlanOp
import com.kirtanya.reel.core.stream.PlayerSignals
import com.kirtanya.reel.core.stream.ProbeStep
import com.kirtanya.reel.core.stream.ReaderView
import com.kirtanya.reel.core.stream.SchedulerProfile
import com.kirtanya.reel.core.stream.StartupPlanner
import com.kirtanya.reel.core.stream.StartupSet
import com.kirtanya.reel.core.stream.StorageAllowance
import com.kirtanya.reel.core.stream.StreamPhase
import com.kirtanya.reel.core.stream.ZoneScheduler
import com.kirtanya.reel.core.stream.PieceGate
import com.kirtanya.reel.core.stream.ReaderKind
import com.kirtanya.reel.core.stream.ReaderListener
import com.kirtanya.reel.core.stream.StreamErrorException
import com.kirtanya.reel.core.stream.StreamErrorKind
import com.kirtanya.reel.core.stream.StreamGoneException
import com.kirtanya.reel.core.stream.StreamReaderCore
import com.kirtanya.reel.core.stream.TorrentStreamRef
import com.kirtanya.reel.core.stream.RestoreFlags
import com.kirtanya.reel.core.stream.RestoreMeta
import com.kirtanya.reel.core.stream.RestorePolicy
import com.kirtanya.reel.core.stream.SessionTuning
import com.kirtanya.reel.core.stream.TorrentFileEntry
import com.kirtanya.reel.core.stream.TorrentKey
import com.kirtanya.reel.core.stream.GiB
import com.kirtanya.reel.core.stream.NightRule
import com.kirtanya.reel.core.stream.WatchCap
import com.kirtanya.reel.core.stream.WatchGovernor
import com.kirtanya.reel.core.stream.mimeTypeFor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.InfoHash
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.PerformanceAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.session_params
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t

/**
 * One libtorrent session for the whole app. Every torrent is either streaming (play now,
 * cached in app storage) or downloading (kept). See docs/PLAN.md §1.6 and docs/STREAMING_V2.md.
 *
 * Every map is keyed by the torrent's canonical key ([TorrentKey]): v1 hex, lowercase.
 */
class TorrentEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Created on first use (start()), so building the engine never loads native code (screen renders on the Mac).
    private val session by lazy { ReelSession() }
    private val handles get() = SessionHandle(session.swig())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** File writes and deletes, in order, off the alert and main threads. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "reel-io").apply { isDaemon = true } }

    /** Streams are cached per torrent under here and trimmed to the cache budget. */
    val streamDir = File(context.filesDir, "streams")
    private val stateDir = File(context.filesDir, "torrents")
    private val sessionStateFile get() = File(stateDir, "session.dht")

    private val is64 = is64Bit()
    private val formFactor by lazy { if (runCatching { (context.applicationContext as com.kirtanya.reel.ReelApp).isTv }.getOrDefault(false)) FormFactor.TV else FormFactor.PHONE }
    private val disk = if (is64) DiskBackend.MMAP else DiskBackend.POSIX

    private val metas = ConcurrentHashMap<String, TorrentMeta>()
    private val errors = ConcurrentHashMap<String, String>()
    private val stalls = ConcurrentHashMap<String, Int>()
    /** One per torrent; completed by METADATA_RECEIVED. Waiters never remove it (lost-wakeup fix). */
    private val metadataWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** Per torrent: which pieces readers may read, and where they wait for more (docs/STREAMING_V2.md §5.1). */
    private val gates = ConcurrentHashMap<String, PieceGate>()
    /** PIECE_FINISHED for a torrent whose gate doesn't exist yet (magnet before metadata). */
    private val preGate = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Int>>()
    private val tokens = AtomicLong()
    /** Plans deadlines, polls flushes and runs safety checks, off the reader threads. */
    private val engineExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "reel-engine").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 } }
    private val server by lazy { com.kirtanya.reel.server.LocalStreamServer(this) }
    private val streams = ConcurrentHashMap<String, ActiveStream>()
    /** Players currently starting a stream on a torrent (hash → count): cache trimming leaves these alone. */
    private val starting = ConcurrentHashMap<String, Int>()
    /** Makes "register a stream + resume" and "unregister + pause / delete" atomic with each other. */
    private val streamLock = Any()
    private val readerIds = AtomicLong()
    /** Shutdown: keys whose final resume save is still owed, and the latch counting them down. */
    @Volatile private var pendingFinal: MutableSet<String>? = null
    @Volatile private var finalLatch: CountDownLatch? = null
    @Volatile private var lastStateSaveAt = 0L

    /** Overnight window + speed cap while watching (docs/STREAMING_V2.md §8.3). */
    private val rulesStore by lazy { DownloadRulesStore.get(context) }
    private val nightAlarm by lazy { NightAlarm(context) }
    private val governor = WatchGovernor()
    /** Engine thread only: per capped torrent, the (download, upload) limits last written. */
    private val capped = HashMap<String, Pair<Long, Long>>()
    @Volatile private var cappedKeys: Set<String> = emptySet()
    private var capping = false
    private var loggedPer = 0L

    private val _torrents = MutableStateFlow<List<TorrentSnapshot>>(emptyList())
    val torrents: StateFlow<List<TorrentSnapshot>> = _torrents.asStateFlow()

    @Volatile private var started = false

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        // Whoever starts the engine (a download, a stream, sync) also needs the foreground service,
        // or Android may kill the process in the background. Not allowed from the background on 12+; then
        // the app's next launch starts it.
        runCatching { EngineService.start(context) }
        EngineFlags.load(context)
        streamDir.mkdirs()
        stateDir.mkdirs()
        session.addListener(listener)
        session.start(sessionParams())
        restore()
        scope.launch { pollLoop() }
        scope.launch { resumeSaveLoop() }
        startRules()
        Log.i(TAG, "engine started (32-bit=${!is64}, $formFactor, $disk, flags=${EngineFlags.summary()})")
    }

    /**
     * Saves resume data for every torrent and waits (at most 5 s) until each one is written, then
     * saves the DHT routing table and stops the session. Runs on the "reel-quit" thread.
     */
    fun shutdown() {
        if (!started) return
        runCatching {
            session.pause()
            val live = handles.torrents().filter { it.isValid && metas.containsKey(it.key()) }
            val keys = ConcurrentHashMap.newKeySet<String>().apply { addAll(live.map { it.key() }) }
            val latch = CountDownLatch(keys.size)
            pendingFinal = keys
            finalLatch = latch
            live.forEach { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.FLUSH_DISK_CACHE)) }
            latch.await(5, TimeUnit.SECONDS)
        }
        runCatching { saveSessionState(blocking = true) }
        io.shutdown()
        runCatching { io.awaitTermination(2, TimeUnit.SECONDS) }
        nightAlarm.hold(false)
        session.stop()
        started = false
    }

    private fun sessionParams(): SessionParams {
        // The saved DHT routing table and node id make the first peer lookups fast after a restart.
        val saved = runCatching { sessionStateFile.takeIf { it.isFile }?.readBytes()?.let { SessionParams(it) } }.getOrNull()
        val params = saved ?: SessionParams()
        params.setSettings(settingsPack(SessionTuning.of(formFactor, disk)))
        // libtorrent 2's memory-mapped disk I/O can exhaust a 32-bit address space (the TV).
        if (disk == DiskBackend.POSIX) params.setPosixDiskIO()
        Log.i(TAG, "session state ${if (saved != null) "restored" else "fresh"}")
        return params
    }

    private fun settingsPack(t: SessionTuning): SettingsPack {
        val sp = SettingsPack()
        fun b(k: settings_pack.bool_types, v: Boolean) = sp.setBoolean(k.swigValue(), v)
        fun i(k: settings_pack.int_types, v: Int) = sp.setInteger(k.swigValue(), v)
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        b(settings_pack.bool_types.enable_upnp, true)
        b(settings_pack.bool_types.enable_natpmp, true)
        sp.alertQueueSize(t.alertQueueSize)
        i(settings_pack.int_types.alert_mask, ReelSession.ALERT_MASK)
        b(settings_pack.bool_types.announce_to_all_trackers, t.announceToAllTrackers)
        b(settings_pack.bool_types.announce_to_all_tiers, t.announceToAllTiers)
        sp.setMaxMetadataSize(t.maxMetadataSize)
        sp.connectionsLimit(t.connectionsLimit)
        i(settings_pack.int_types.connection_speed, t.connectionSpeed)
        i(settings_pack.int_types.torrent_connect_boost, t.torrentConnectBoost)
        b(settings_pack.bool_types.smooth_connects, t.smoothConnects)
        i(settings_pack.int_types.peer_connect_timeout, t.peerConnectTimeout)
        i(settings_pack.int_types.handshake_timeout, t.handshakeTimeout)
        i(settings_pack.int_types.min_reconnect_time, t.minReconnectTime)
        i(settings_pack.int_types.piece_timeout, t.pieceTimeout)
        i(settings_pack.int_types.request_timeout, t.requestTimeout)
        i(settings_pack.int_types.request_queue_time, t.requestQueueTime)
        i(settings_pack.int_types.max_out_request_queue, t.maxOutRequestQueue)
        t.maxQueuedDiskBytes?.let { sp.maxQueuedDiskBytes(it) }
        i(settings_pack.int_types.unchoke_slots_limit, t.unchokeSlotsLimit)
        sp.activeDownloads(t.activeDownloads)
        sp.activeSeeds(t.activeSeeds)
        sp.activeLimit(t.activeLimit)
        t.inactiveDownRate?.let { i(settings_pack.int_types.inactive_down_rate, it) }
        t.inactiveUpRate?.let { i(settings_pack.int_types.inactive_up_rate, it) }
        i(settings_pack.int_types.dht_announce_interval, t.dhtAnnounceInterval)
        sp.stopTrackerTimeout(t.stopTrackerTimeout)
        t.maxPeerlistSize?.let { sp.maxPeerlistSize(it) }
        t.maxPausedPeerlistSize?.let { i(settings_pack.int_types.max_paused_peerlist_size, it) }
        return sp
    }

    /** Writes the DHT routing table so the next start doesn't bootstrap from scratch. */
    private fun saveSessionState(blocking: Boolean = false) {
        val bytes = runCatching {
            val st = session.swig()?.session_state(session_handle.save_dht_state) ?: return
            Vectors.byte_vector2bytes(session_params.write_session_params_buf(st, session_handle.save_dht_state))
        }.getOrNull() ?: return
        lastStateSaveAt = System.currentTimeMillis()
        val write = Runnable { atomicWrite(sessionStateFile, bytes) }
        if (blocking) write.run() else runCatching { io.execute(write) }
    }

    // ---------------------------------------------------------------- adding

    /**
     * Adds a magnet link (or returns the existing torrent). Returns the info-hash.
     * For downloads, [onlyFileIndex] / [onlyFileHint] add one file (e.g. one episode of a season pack)
     * to what's wanted; earlier choices are kept.
     */
    suspend fun addMagnet(uri: String, mode: TorrentMode, onlyFileIndex: Int? = null, onlyFileHint: String? = null): String =
        addMagnetInternal(uri, mode, onlyFileIndex, onlyFileHint, streamFileIdx = null).first

    /** Returns (key, created). [streamFileIdx] lets a fresh stream add download nothing but that file. */
    private suspend fun addMagnetInternal(uri: String, mode: TorrentMode, onlyFileIndex: Int?, onlyFileHint: String?, streamFileIdx: Int?): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            start()
            val magnet = Magnet.parse(uri) ?: throw EngineException("That isn't a valid magnet link")
            val hash = magnet.infoHash
            find(hash)?.let { existing ->
                if (mode == TorrentMode.DOWNLOAD) wantForDownload(existing, hash, onlyFileIndex, onlyFileHint)
                return@withContext hash to false
            }
            val params = try {
                AddTorrentParams.parseMagnetUri(uri.trim())
            } catch (t: Throwable) {
                throw EngineException("Couldn't read the magnet link")
            }
            params.setTrackers((params.getTrackers() + DEFAULT_TRACKERS).distinct())
            add(hash, params, mode, onlyFileIndex, onlyFileHint, streamFileIdx)
            hash to true
        }

    /** Adds a `.torrent` file's bytes. Returns the info-hash. */
    suspend fun addTorrentFile(bytes: ByteArray, mode: TorrentMode): String = withContext(Dispatchers.IO) {
        start()
        val info = try {
            TorrentInfo.bdecode(bytes)
        } catch (t: Throwable) {
            throw EngineException("That .torrent file is damaged")
        }
        val hash = keyOf(info.infoHashes())
        find(hash)?.let { existing ->
            if (mode == TorrentMode.DOWNLOAD) wantForDownload(existing, hash, null, null)
            return@withContext hash
        }
        val params = AddTorrentParams()
        params.setTorrentInfo(info)
        // Public trackers only for public torrents: a private one would leak to them.
        if (!info.isPrivate) params.setTrackers((params.getTrackers() + DEFAULT_TRACKERS).distinct())
        add(hash, params, mode)
        hash
    }

    private fun add(hash: String, params: AddTorrentParams, mode: TorrentMode, onlyFileIndex: Int? = null, onlyFileHint: String? = null, streamFileIdx: Int? = null) {
        val save = saveDirFor(hash, mode)
        params.setSavePath(save.absolutePath)
        var flags = flagsFor(mode, params.getFlags())
        // Home disk P2P rule (docs/HOME_DISK.md §5.3): a download added where it must wait starts paused, with no traffic.
        // Overnight window (§8.3 A): a new download outside it waits, paused; the window releases it (and asks the P2P rule then).
        val nightHeld = mode == TorrentMode.DOWNLOAD &&
            NightRule.holds(rulesStore.rules.value.night, minuteNow(), download = true, startNow = false, finished = false, claimed = false)
        if (nightHeld || (mode == TorrentMode.DOWNLOAD && downloadHold(hash))) flags = flags.without(TorrentFlags.AUTO_MANAGED).or_(TorrentFlags.PAUSED)
        if (mode == TorrentMode.STREAM && streamFileIdx != null && streamFileIdx >= 0) {
            // Fetch nothing but the chosen file: without this, an abandoned start (Back during
            // "Finding peers…") downloaded the whole torrent once metadata arrived.
            flags = flags.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            params.filePriorities(Array(streamFileIdx + 1) { if (it == streamFileIdx) Priority.DEFAULT else Priority.IGNORE })
        }
        val intent = FileChoice.merge(com.kirtanya.reel.core.stream.FileIntent(), onlyFileIndex, onlyFileHint)
        if (mode == TorrentMode.DOWNLOAD && !intent.fullTorrent) {
            // One episode of a pack: every file starts unwanted, then the intent raises the chosen one.
            flags = flags.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            if (onlyFileIndex != null && onlyFileIndex >= 0) {
                params.filePriorities(Array(onlyFileIndex + 1) { if (it == onlyFileIndex) Priority.DEFAULT else Priority.IGNORE })
            }
        }
        params.setFlags(flags)
        val meta = TorrentMeta(hash, mode, schema = 2, savePath = save.absolutePath, createdBy = if (mode == TorrentMode.STREAM) "stream" else "user")
            .let { if (mode == TorrentMode.DOWNLOAD) it.withIntent(intent) else it }
            .copy(pendingFileIntent = mode == TorrentMode.DOWNLOAD && !intent.fullTorrent, pausedForNight = nightHeld)
        metadataWaiters.getOrPut(hash) { CompletableDeferred() }
        saveMeta(meta)
        val ec = ErrorCode(error_code())
        val handle = handles.addTorrent(params, ec)
        if (ec.isError || !handle.isValid) {
            metas.remove(hash)
            File(stateDir, "$hash.json").delete()
            throw EngineException(ec.message.ifBlank { "Couldn't add the torrent" })
        }
        applyFileIntent(handle)
        handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    /** A Download on a torrent we already have: add the file to what's wanted (never narrow). */
    private fun wantForDownload(handle: TorrentHandle, hash: String, onlyFileIndex: Int?, onlyFileHint: String?) {
        val meta = metas[hash] ?: return
        if (meta.mode == TorrentMode.STREAM) {
            keepInternal(handle, meta, FileChoice.merge(com.kirtanya.reel.core.stream.FileIntent(), onlyFileIndex, onlyFileHint))
            return
        }
        val merged = FileChoice.merge(meta.intent, onlyFileIndex, onlyFileHint)
        saveMeta(meta.withIntent(merged).copy(pendingFileIntent = true))
        applyFileIntent(handle)
        // A finished download that wants one more episode is unfinished again: the overnight window may hold it.
        kickRules()
    }

    /**
     * DOWNLOAD torrents: make the wanted files' priorities explicit once metadata is known.
     * Raises only, so a second episode never cancels the first.
     */
    private fun applyFileIntent(handle: TorrentHandle) {
        val key = handle.key()
        val meta = metas[key] ?: return
        if (meta.mode != TorrentMode.DOWNLOAD || !meta.pendingFileIntent) return
        val info = handle.torrentFile() ?: return // no metadata yet; METADATA_RECEIVED calls again
        val files = files(key)
        val explicit = FileChoice.explicitPriorities(meta.intent, files, info.numFiles())
        val current = handle.filePriorities().map { it.swig().toInt() }.toIntArray()
        val merged = FileChoice.raiseOnly(current, explicit)
        handle.prioritizeFiles(Array(info.numFiles()) { Priority.fromSwig(merged.getOrElse(it) { 0 }) })
        saveMeta(meta.copy(pendingFileIntent = false))
    }

    private fun saveDirFor(hash: String, mode: TorrentMode): File = when (mode) {
        TorrentMode.STREAM -> File(streamDir, hash)
        TorrentMode.DOWNLOAD -> downloadsDir()
    }.apply { mkdirs() }

    /** Public Downloads/Reel when "All files access" is granted, else the app's own folder. */
    fun downloadsDir(): File {
        val canUsePublic = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        val dir = if (canUsePublic) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Reel")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Reel")
        }
        return dir.apply { mkdirs() }
    }

    private fun flagsFor(mode: TorrentMode, base: torrent_flags_t): torrent_flags_t {
        var flags = base
        // Streams must never be paused by the download queue.
        flags = if (mode == TorrentMode.STREAM) flags.without(TorrentFlags.AUTO_MANAGED) else flags.or_(TorrentFlags.AUTO_MANAGED)
        return flags.without(TorrentFlags.PAUSED)
    }

    // ---------------------------------------------------------------- controls

    fun pause(hash: String) {
        val h = find(hash) ?: return
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
        metas[hash]?.let { saveMeta(it.copy(userPaused = true)) }
    }

    fun resume(hash: String) {
        val h = find(hash) ?: return
        errors.remove(hash)
        runCatching { h.swig().clear_error() }
        metas[hash]?.let { meta ->
            // Outside the overnight window a Resume (the user's, or the P2P rule's release) moves no traffic:
            // the download goes back to waiting for night. "Start now" is the way to run it anyway.
            if (nightHolds(meta, h)) {
                h.unsetFlags(TorrentFlags.AUTO_MANAGED)
                h.pause()
                saveMeta(meta.copy(userPaused = false, pausedForSpace = false, pausedForNight = true))
                refreshSnapshotsSoon()
                return
            }
        }
        if (metas[hash]?.mode == TorrentMode.DOWNLOAD) {
            // A queued download the user asks for goes to the front of the queue.
            if (h.getFlags().and_(TorrentFlags.AUTO_MANAGED).non_zero() && h.getFlags().and_(TorrentFlags.PAUSED).non_zero()) h.queuePositionTop()
            h.setFlags(TorrentFlags.AUTO_MANAGED)
        }
        h.resume()
        metas[hash]?.let { saveMeta(it.copy(userPaused = false, pausedForSpace = false, pausedForNight = false)) }
    }

    /**
     * "Start now" on a download waiting for night (§8.3 A): the window never holds it again. Still obeys the home
     * disk's P2P rule: on a network where downloads wait, the P2P gate starts it once the network allows.
     */
    fun startNow(hash: String) {
        val h = find(hash) ?: return
        val meta = metas[hash] ?: return
        saveMeta(meta.copy(startNow = true, pausedForNight = false, userPaused = false))
        EngineStats.log("nightStartNow", mapOf("key" to hash.take(12)))
        if (meta.mode == TorrentMode.DOWNLOAD && downloadHold(hash)) {
            refreshSnapshotsSoon()
            return
        }
        runCatching { h.queuePositionTop() }
        resume(hash)
    }

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = find(hash)
        streams.remove(hash)?.let { release(it, StreamGoneException("This stream was removed")) }
        gates.remove(hash)?.close(StreamGoneException("This stream was removed"))
        preGate.remove(hash)
        metadataWaiters.remove(hash)?.completeExceptionally(EngineException("This stream was removed"))
        errors.remove(hash)
        metas.remove(hash)
        // libtorrent deletes the files itself (DELETE_FILES); the stream folder is swept when empty.
        if (h != null) {
            if (deleteFiles) handles.removeTorrent(h, SessionHandle.DELETE_FILES) else handles.removeTorrent(h)
        }
        runCatching {
            io.execute {
                File(stateDir, "$hash.resume").delete()
                File(stateDir, "$hash.json").delete()
            }
        }
        refreshSnapshots()
    }

    /** "Keep" / "Save as download": a stream becomes a normal download in the Downloads folder; fetched pieces are reused. */
    fun keep(hash: String) {
        val h = find(hash) ?: return
        val meta = metas[hash] ?: return
        when (ModeChange.toDownload(modeFacts(hash, meta))) {
            ModeStep.NONE -> Unit
            ModeStep.CANCEL_PENDING -> saveMeta(meta.copy(cachePending = false))
            // keepInternal defers by itself while a player holds the torrent.
            ModeStep.DEFER, ModeStep.NOW -> keepInternal(h, meta, com.kirtanya.reel.core.stream.FileIntent())
        }
    }

    /**
     * "Keep as stream cache" (docs/STREAMING_V2.md §7.3a): a download stops being kept. Its data stays
     * where it is and becomes a stream-cache entry under the normal cache policy, so it can still play.
     */
    fun keepAsCache(hash: String) {
        val h = find(hash) ?: return
        val meta = metas[hash] ?: return
        when (ModeChange.toCache(modeFacts(hash, meta))) {
            ModeStep.NONE -> Unit
            ModeStep.CANCEL_PENDING -> saveMeta(meta.copy(keepPending = false))
            ModeStep.DEFER -> saveMeta(meta.copy(cachePending = true))
            ModeStep.NOW -> {
                cacheInternal(h, meta)
                scope.launch(Dispatchers.IO) { runCatching { trimCache(DEFAULT_CACHE_BUDGET) } }
            }
        }
        refreshSnapshots()
    }

    private fun modeFacts(hash: String, meta: TorrentMeta) = ModeFacts(
        isDownload = meta.mode == TorrentMode.DOWNLOAD,
        claimed = streams.containsKey(hash) || starting.containsKey(hash),
        keepPending = meta.keepPending,
        cachePending = meta.cachePending,
    )

    private fun cacheInternal(h: TorrentHandle, meta: TorrentMeta) {
        // Not queued, not running: a cache entry no player reads stays paused (§7.2).
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
        val snapshotFile = snapshot(meta.infoHash)?.fileIndex
        saveMeta(
            meta.copy(
                mode = TorrentMode.STREAM,
                fullTorrent = false, wantedFiles = emptyList(), wantedHints = emptyList(), pendingFileIntent = false,
                userPaused = false, pausedForSpace = false, seedingDone = false, keepPending = false, cachePending = false,
                streamFileIndex = ModeChange.cacheStreamFile(meta.streamFileIndex, meta.wantedFiles, snapshotFile),
                lastUsedAt = ModeChange.cacheLastUsedAt(System.currentTimeMillis(), meta.lastUsedAt),
            ),
        )
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        Log.i(TAG, "kept as stream cache ${meta.infoHash}")
    }

    private fun keepInternal(h: TorrentHandle, meta: TorrentMeta, intent: com.kirtanya.reel.core.stream.FileIntent) {
        val key = meta.infoHash
        // Moving the files while a player reads them breaks the player: do it when the stream ends.
        if (streams.containsKey(key) || starting.containsKey(key)) {
            val wanted = if (intent.fullTorrent || (intent.wantedFiles.isEmpty() && intent.wantedHints.isEmpty())) intent.copy(fullTorrent = true) else intent
            saveMeta(meta.copy(keepPending = true, cachePending = false).withIntent(wanted))
            return
        }
        // Never overwrite a file of the same name already in Downloads. A download that was kept as
        // stream cache never left the Downloads folder (§7.3a): nothing to move.
        val current = meta.savePath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        if (current != downloadsDir().canonicalPath) h.moveStorage(downloadsDir().absolutePath, org.libtorrent4j.MoveFlags.FAIL_IF_EXIST)
        h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
        // A stream added for one file downloads nothing else: say explicitly what Keep wants.
        val keepIntent = if (intent.fullTorrent || (intent.wantedFiles.isEmpty() && intent.wantedHints.isEmpty())) intent.copy(fullTorrent = true) else intent
        saveMeta(meta.copy(mode = TorrentMode.DOWNLOAD, savePath = downloadsDir().absolutePath, pendingFileIntent = true, keepPending = false, cachePending = false).withIntent(keepIntent))
        applyFileIntent(h)
        // Now a download: the overnight window applies from here on.
        kickRules()
    }

    fun files(hash: String): List<TorrentFileEntry> {
        val info = find(hash)?.torrentFile() ?: return emptyList()
        val fs = info.files()
        return (0 until fs.numFiles())
            .filterNot { fs.padFileAt(it) }
            .map { TorrentFileEntry(it, fs.filePath(it), fs.fileSize(it)) }
    }

    fun snapshot(hash: String): TorrentSnapshot? = _torrents.value.firstOrNull { it.infoHash == hash }

    /** Torrents a player is streaming right now. */
    fun streamingKeys(): Set<String> = streams.keys.toSet()

    /**
     * What a problem report needs about one torrent (docs/MACHINE_FIRST_PLAN.md step 1): the UI
     * snapshot plus, while it streams, the file and how much of it is on disk. Only reads plain
     * fields (no libtorrent calls), so it is safe from any thread, even a crash handler.
     */
    fun reportState(hash: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("key" to hash)
        snapshot(hash)?.let { s ->
            out += mapOf(
                "name" to s.name, "mode" to s.mode.name, "phase" to s.phase.name, "progress" to s.progress,
                "peers" to s.peers, "seeds" to s.seeds, "downBps" to s.downBps, "upBps" to s.upBps,
                "doneBytes" to s.doneBytes, "wantedBytes" to s.wantedBytes, "stalledForSec" to s.stalledForSec,
                "error" to s.error, "userPaused" to s.userPaused,
            )
        }
        streams[hash]?.let { s ->
            val span = s.info.span
            out += mapOf(
                "file" to s.info.file.path, "fileSize" to s.info.file.size, "mime" to s.info.mimeType,
                "pieceLength" to span.pieceLength,
                "filePieces" to span.pieceRange.count(),
                "filePiecesHave" to span.pieceRange.count { s.gate.pieces.has(it) },
                "torrentPiecesHave" to s.gate.pieces.count(), "torrentPieces" to s.numPieces,
                "readers" to s.readers.size, "background" to s.background, "startupDone" to s.startupDone,
                "bytesPerSec" to s.bytesPerSec, "playerPosMs" to s.signals.positionMs, "playerBufMs" to s.signals.bufferedPositionMs,
                "streamAgeMs" to EngineStats.now() - s.startedAt,
            )
        }
        return out
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Waits for metadata, picks the file, focuses the torrent on it and queues the startup pieces.
     * Readers can open [StreamInfo.uri] (in-app) or [httpUrlFor] (external players) once this returns.
     */
    suspend fun prepareStream(hash: String, fileIdx: Int? = null, filenameHint: String? = null, registered: ((StreamInfo) -> Unit)? = null): StreamInfo =
        withContext(Dispatchers.IO) {
            val handle = find(hash) ?: throw EngineException("Torrent not found")
            awaitMetadata(hash, handle, METADATA_TIMEOUT_MS)
            EngineStats.of(hash)?.meta(EngineStats.now())
            EngineStats.event(hash, "meta", mapOf("peers" to runCatching { handle.status().numPeers() }.getOrNull()))
            val info = handle.torrentFile() ?: throw EngineException("No metadata yet")
            val fs = info.files()
            val meta = metas[hash]
            val mode = meta?.mode ?: TorrentMode.STREAM
            // Downloads: never guess a file the user didn't download (an IGNORE'd one).
            val ignored: (Int) -> Boolean = if (mode == TorrentMode.DOWNLOAD) { i -> runCatching { handle.filePriority(i) == Priority.IGNORE }.getOrDefault(false) } else { _ -> false }
            val file = FileChoice.pickStream(files(hash), fileIdx, filenameHint, meta?.intent ?: com.kirtanya.reel.core.stream.FileIntent(), ignored)
                ?: throw EngineException("This torrent has no files")

            // Intended file priorities; when they change, readers wait for libtorrent to apply them
            // (pieces fetched while the file was ignored sit in the part file until then).
            val current = runCatching { handle.filePriorities().map { it.swig().toInt() } }.getOrDefault(emptyList())
            val intended = if (mode == TorrentMode.STREAM) List(fs.numFiles()) { if (it == file.index) 4 else 0 }
                else current.mapIndexed { i, v -> if (i == file.index && v == 0) 4 else v }
            val changed = intended != current
            if (changed) handle.prioritizeFiles(Array(intended.size) { Priority.fromSwig(intended[it]) })
            val gate = ensureGate(hash, handle, info.numPieces())
            val span = FileSpan(fs.fileOffset(file.index), fs.fileSize(file.index), info.pieceLength(), info.numPieces())
            val token = tokens.incrementAndGet()
            val stream = StreamInfo(
                infoHash = hash,
                file = file,
                span = span,
                mimeType = mimeTypeFor(file.name),
                path = File(handle.savePath(), fs.filePath(file.index)),
                token = token,
                uri = TorrentStreamRef(hash, file.index, token, file.name).format(),
            )
            metas[hash]?.let { saveMeta(it.copy(streamFileIndex = file.index, lastUsedAt = System.currentTimeMillis())) }
            val profile = SchedulerProfile.of(formFactor, disk)
            val baseline = baselineVector(fs, info.numPieces(), info.pieceLength(), intended)
            val active = ActiveStream(stream, handle, gate, profile, info.numPieces(), baseline, download = mode == TorrentMode.DOWNLOAD)
            active.startupSet = StartupPlanner.initial(span, file.extension, startMs = 0, durationMs = null, rate = MediaRate.of(file.size, null, null), profile = profile)
            active.fileReady = !changed
            active.intendedPrios = intended
            if (mode == TorrentMode.DOWNLOAD && current.getOrElse(file.index) { 4 } == 0) active.raisedFile = file.index
            synchronized(streamLock) {
                // A previous stream on this torrent (the last episode of a season pack): its readers
                // fail fast; the torrent and its gate carry on for the new one.
                streams[hash]?.let { old -> supersede(old) }
                // Register and resume together: a player stopping at the same moment can't pause us after.
                streams[hash] = active
                handle.resume()
                registered?.invoke(stream)
            }
            // Readers go once libtorrent reports the new file priorities in force (checked, not assumed).
            if (changed) engineExec.schedule({ releaseFileBarrier(active, timedOut = false) }, 50, TimeUnit.MILLISECONDS)
            EngineStats.event(hash, "prepared", mapOf("size" to file.size, "pl" to info.pieceLength(), "pieces" to info.numPieces(), "have" to gate.pieces.count()))
            kick(active, immediate = true)
            stream
        }

    /**
     * What a player calls: adds the torrent (stream mode) and prepares the file, holding a claim
     * on the torrent the whole time so a finishing player's cache trim can't delete it underneath.
     * If the start doesn't complete (Back during "Finding peers…", no metadata), the torrent is
     * cleaned up instead of downloading on in the background.
     */
    suspend fun startStream(magnet: String, fileIdx: Int?, filenameHint: String?, tapMs: Long = EngineStats.now()): StreamInfo {
        val hash = Magnet.parse(magnet)?.infoHash ?: throw EngineException("That isn't a valid magnet link")
        // Under the lock, so a cache trim that's mid-removal finishes first and then sees the claim.
        synchronized(streamLock) { starting.merge(hash, 1, Int::plus) }
        var created = false
        var prepared = false
        var registeredInfo: StreamInfo? = null
        try {
            val cached = find(hash) != null
            EngineStats.begin(hash, tapMs, mapOf("dev" to formFactor.name.lowercase(), "disk" to disk.name.lowercase(), "warm" to if (cached) "cached" else "cold", "build" to "p2", "flags" to EngineFlags.summary()))
            created = addMagnetInternal(magnet, TorrentMode.STREAM, null, null, streamFileIdx = fileIdx).second
            find(hash)?.let { h ->
                // A paused torrent (cached stream, or a download) must run to fetch metadata at all.
                synchronized(streamLock) { wakeForStream(hash, h) }
                runCatching { h.forceDHTAnnounce() }
                // A cached stream that was paused: peers are long gone, ask everyone again now.
                if (cached) runCatching { h.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL) }
            }
            return prepareStream(hash, fileIdx, filenameHint) { registeredInfo = it }.also { prepared = true }
        } finally {
            withContext(NonCancellable) {
                starting.computeIfPresent(hash) { _, n -> (n - 1).takeIf { it > 0 } }
                if (!prepared) {
                    // Registered, then cancelled before the caller got it: nobody else can stop it.
                    registeredInfo?.let { stopStream(hash, it) }
                    abandon(hash, created)
                }
            }
        }
    }

    /** A start that didn't complete: drop what it added, or park it, unless someone else uses it. */
    private fun abandon(hash: String, created: Boolean) = synchronized(streamLock) {
        if (starting.containsKey(hash) || streams.containsKey(hash)) return@synchronized
        EngineStats.end(hash, mapOf("abandoned" to 1))
        val meta = metas[hash] ?: return@synchronized
        val h = find(hash) ?: return@synchronized
        if (meta.mode != TorrentMode.STREAM) {
            restoreDownloadFlags(h, meta, raisedFile = null)
            return@synchronized
        }
        val done = runCatching { h.status().totalDone() }.getOrDefault(0L)
        if (created && done == 0L) {
            Log.i(TAG, "start abandoned; removing ${hash.take(8)}")
            remove(hash, deleteFiles = true)
        } else {
            Log.i(TAG, "start abandoned; pausing ${hash.take(8)}")
            h.clearPieceDeadlines()
            h.pause()
        }
    }

    fun streamInfo(hash: String): StreamInfo? = streams[hash]?.info

    fun isStreaming(hash: String): Boolean = streams.containsKey(hash)

    /** The player's duration is known: size the read-ahead in seconds of video, not a fixed size. */
    fun setStreamDuration(owner: StreamInfo, durationMs: Long) {
        val s = streams[owner.infoHash]?.takeIf { it.info === owner } ?: return
        if (durationMs <= 0) return
        val rate = owner.file.size * 1000 / durationMs
        if (s.bytesPerSec == rate) return
        s.bytesPerSec = rate
        s.playerDurationMs = durationMs
        EngineStats.event(owner.infoHash, "rate", mapOf("B" to rate, "src" to "player"))
        kick(s, immediate = false)
    }

    /**
     * The player went to the background: stop fetching [owner]'s stream-mode torrent so it doesn't
     * eat bandwidth (e.g. while another app plays). Downloads and other players' streams are untouched.
     */
    fun suspendStream(hash: String, owner: StreamInfo?) {
        if (owner == null) return
        synchronized(streamLock) {
            streams[hash]?.takeIf { it.info === owner }?.let { it.background = true }
            streamHandleIfOwned(hash, owner)?.pause()
        }
    }

    /** The player is visible again: carry on fetching. */
    fun resumeStream(hash: String, owner: StreamInfo?) {
        if (owner == null) return
        val s = synchronized(streamLock) {
            streams[hash]?.takeIf { it.info === owner }?.also { st ->
                st.background = false
                streamHandleIfOwned(hash, owner)?.resume()
            }
        }
        s?.let { kick(it, immediate = true) }
    }

    /** Has an external player read [owner]'s stream recently (a request open now, or within [windowMs])? */
    fun hasRecentActivity(hash: String, owner: StreamInfo?, windowMs: Long = 30_000): Boolean {
        val s = streams[hash]?.takeIf { owner == null || it.info === owner } ?: return false
        return s.readers.values.any { it.kind == ReaderKind.HTTP } || System.currentTimeMillis() - s.lastHttpActivityAt < windowMs
    }

    /** The player's live state (position, buffer, playing), read by the scheduler. */
    fun playerSignals(owner: StreamInfo): PlayerSignals? = streams[owner.infoHash]?.takeIf { it.info === owner }?.signals

    /** First frame, seeks and hand-offs replan at once. */
    fun onPlayerEvent(owner: StreamInfo, event: String) {
        val s = streams[owner.infoHash]?.takeIf { it.info === owner } ?: return
        if (event == "firstFrame") s.signals.firstFrame = true
        kick(s, immediate = true)
    }

    private fun streamHandleIfOwned(hash: String, owner: StreamInfo?): TorrentHandle? = synchronized(streamLock) {
        val s = streams[hash] ?: return null
        if (owner != null && s.info !== owner) return null
        if (metas[hash]?.mode != TorrentMode.STREAM) return null
        find(hash)
    }

    /** Is a player reading [owner]'s stream right now? By default counts external (HTTP) players only. */
    fun hasReaders(hash: String, owner: StreamInfo?, kind: ReaderKind? = ReaderKind.HTTP): Boolean =
        streams[hash]?.takeIf { owner == null || it.info === owner }?.readers?.values?.any { kind == null || it.kind == kind } == true

    /**
     * Called when a player is done with [owner] (the stream it prepared). If another player has
     * since prepared a different file of the same torrent (next episode of a season pack), its
     * stream is left alone. Stream-mode torrents pause and stay in the cache.
     */
    fun stopStream(hash: String, owner: StreamInfo? = null) {
        val stream: ActiveStream
        synchronized(streamLock) {
            stream = streams[hash] ?: return
            if (owner != null && stream.info !== owner) return
            if (!streams.remove(hash, stream)) return
            release(stream, StreamGoneException("This stream stopped"))
            metas[hash]?.let { saveMeta(it.copy(lastStoppedAt = System.currentTimeMillis())) }
            // Another player is starting this torrent (next episode of a season pack): leave it running.
            if (starting.containsKey(hash) || streams.containsKey(hash)) return
            EngineStats.end(hash)
            val h = find(hash) ?: return
            val meta = metas[hash]
            if (meta?.mode == TorrentMode.STREAM) {
                h.pause()
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
                if (meta.keepPending) keepInternal(h, meta, meta.intent)
            } else if (meta != null) {
                restoreDownloadFlags(h, meta, stream.raisedFile)
                if (meta.cachePending) metas[hash]?.let { cacheInternal(h, it) }
            }
        }
        Log.i(TAG, "stream stopped ${stream.info.file.name}")
        // A download played outside the overnight window goes back to waiting; the cap may lift.
        kickRules()
        // The first stop after a while also refreshes the saved DHT table.
        if (System.currentTimeMillis() - lastStateSaveAt > 60_000) saveSessionState()
        scope.launch(Dispatchers.IO) { runCatching { trimCache(DEFAULT_CACHE_BUDGET) } }
    }

    /** Readers of [old] fail fast; its deadlines are cleared (the torrent and gate carry on). */
    private fun supersede(old: ActiveStream) = release(old, StreamGoneException("superseded"), restorePriorities = false)

    private fun release(s: ActiveStream, reason: java.io.IOException, restorePriorities: Boolean = true) {
        s.closedReason = reason
        s.gate.signalAll()
        runCatching { engineExec.execute { clearPlan(s, restorePriorities) } }
    }

    /**
     * Before a stream starts on a paused torrent: a stream torrent just resumes; a download remembers
     * whether the queue managed it, leaves the queue (it would pause it again) and resumes.
     */
    private fun wakeForStream(hash: String, h: TorrentHandle) {
        val meta = metas[hash] ?: return
        val flags = h.getFlags()
        if (meta.mode == TorrentMode.DOWNLOAD) {
            if (meta.wasAutoManaged == null) saveMeta(meta.copy(wasAutoManaged = flags.and_(TorrentFlags.AUTO_MANAGED).non_zero()))
            h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        }
        if (flags.and_(TorrentFlags.PAUSED).non_zero()) h.resume()
    }

    /** A download that was played: give it back to the queue (or keep the user's pause), lower a raised file. */
    private fun restoreDownloadFlags(h: TorrentHandle, meta: TorrentMeta, raisedFile: Int?) {
        runCatching { h.clearPieceDeadlines() }
        runCatching { h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        if (raisedFile != null) {
            val stillWanted = meta.intent.fullTorrent || raisedFile in meta.intent.wantedFiles
            if (!stillWanted) runCatching { h.filePriority(raisedFile, Priority.IGNORE) }
        }
        when {
            meta.userPaused -> { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause() }
            meta.wasAutoManaged == true -> h.setFlags(TorrentFlags.AUTO_MANAGED)
        }
        saveMeta(meta.copy(wasAutoManaged = null))
    }

    /**
     * For a stream handed to an external player: keep serving while it reads, and stop once no
     * player has read anything for [idleMs] (the external app was closed).
     */
    fun stopWhenIdle(hash: String, owner: StreamInfo?, idleMs: Long = 10 * 60_000L) {
        scope.launch {
            while (isActive) {
                delay(60_000)
                val s = streams[hash] ?: return@launch
                if (owner != null && s.info !== owner) return@launch
                if (s.readers.values.none { it.kind == ReaderKind.HTTP } && System.currentTimeMillis() - maxOf(s.lastHttpActivityAt, s.lastActivityAt) > idleMs) {
                    Log.i(TAG, "external player idle; stopping ${s.info.file.name}")
                    stopStream(hash, owner)
                    return@launch
                }
            }
        }
    }

    /** URL an external player (VLC, MX…) can play [info] from, served by the engine's local server. */
    fun httpUrlFor(info: StreamInfo): String = server.urlFor(info)

    /**
     * Opens a reader on a prepared stream at [position]. Reads block until their piece is on disk
     * and fail only when interrupted, stopped, superseded, or (HTTP) when the client goes away.
     */
    fun openReader(ref: TorrentStreamRef, position: Long, kind: ReaderKind, socket: java.net.Socket? = null): StreamReader {
        val s = streams[ref.key]?.takeIf { it.info.token == ref.token && it.info.file.index == ref.fileIndex }
            ?: throw StreamGoneException("This stream is no longer available")
        s.closedReason?.let { throw it }
        val id = readerIds.incrementAndGet()
        val core = StreamReaderCore(
            id = id,
            kind = kind,
            span = s.info.span,
            gate = s.gate,
            opener = { openFile(s) },
            fileReady = { s.fileReady },
            alive = { s.closedReason?.let { throw it } },
            probe = socket?.let { sock -> socketProbe(sock) },
            listener = readerListener(s),
        )
        core.position = position
        s.readers[id] = core
        s.lastActivityAt = System.currentTimeMillis()
        if (kind == ReaderKind.HTTP) s.lastHttpActivityAt = s.lastActivityAt
        s.lastClosedAt = 0
        kick(s, immediate = true)
        return StreamReader(core, s.info.file.size) {
            core.close()
            if (s.readers.remove(id) != null) {
                s.lastActivityAt = System.currentTimeMillis()
                if (kind == ReaderKind.HTTP) s.lastHttpActivityAt = s.lastActivityAt
                s.lastClosedPos = core.position
                s.lastClosedAt = EngineStats.now()
                // Keep the closed reader's window briefly: ExoPlayer closes and reopens on every
                // extractor seek, and the deadlines shouldn't flap.
                engineExec.schedule({ kick(s, immediate = true) }, GHOST_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun openFile(s: ActiveStream): java.io.RandomAccessFile {
        repeat(2) { attempt ->
            if (s.info.path.isFile) return java.io.RandomAccessFile(s.info.path, "r")
            if (attempt == 0) Thread.sleep(1_000)
        }
        throw StreamErrorException(StreamErrorKind.FILE_ERROR, "The video file isn't on disk")
    }

    /** HTTP readers: notices within ~1 s that the external player hung up (seek, close). */
    private fun socketProbe(sock: java.net.Socket): () -> Unit {
        var last = 0L
        return {
            val now = EngineStats.now()
            if (now - last >= 1_000) {
                last = now
                val old = runCatching { sock.soTimeout }.getOrDefault(0)
                try {
                    sock.soTimeout = 1
                    if (sock.getInputStream().read() < 0) throw StreamGoneException("client left")
                } catch (e: java.net.SocketTimeoutException) {
                    // alive
                } catch (e: StreamGoneException) {
                    throw e
                } catch (e: java.io.IOException) {
                    throw StreamGoneException("client left")
                } finally {
                    runCatching { sock.soTimeout = old }
                }
            }
        }
    }

    private fun readerListener(s: ActiveStream) = object : ReaderListener {
        override fun onBlocked(piece: Int) {
            if (!s.startupDone || EngineFlags.verboseStats) {
                EngineStats.event(s.info.infoHash, "block", mapOf("p" to piece, "have" to s.gate.pieces.has(piece).compareTo(false), "readers" to s.readers.size))
            }
            kick(s, immediate = true)
        }

        override fun onUnblocked(piece: Int, waitedMs: Long) {
            if (waitedMs >= 200) {
                runCatching { EngineStats.of(s.info.infoHash)?.readerWait(waitedMs) }
                EngineStats.event(s.info.infoHash, "wait", mapOf("p" to piece, "ms" to waitedMs, "fileReady" to if (s.fileReady) 1 else 0))
            }
        }

        override fun onPieceCrossed(position: Long) {
            EngineStats.of(s.info.infoHash)?.let { st -> if (st.firstByteAt == null) { st.firstByte(EngineStats.now()); EngineStats.event(s.info.infoHash, "byte1", mapOf("pos" to position)) } }
            kick(s, immediate = false)
        }

        override fun needFlush(piece: Int) {
            if (s.flushPolls.add(piece)) engineExec.execute { pollFlush(s, piece, 0) }
        }

        override fun longBlock(piece: Int) {
            engineExec.execute {
                // Safety net: libtorrent has it but we never heard (a missed alert). Should not happen.
                if (!s.gate.pieces.has(piece) && runCatching { s.handle.havePiece(piece) }.getOrDefault(false)) {
                    s.gate.markVerified(piece)
                    EngineStats.event(s.info.infoHash, "missedWake", mapOf("p" to piece))
                }
            }
        }
    }

    /** mmap backend: a verified piece may still be writing; poll until it leaves the download queue. */
    private fun pollFlush(s: ActiveStream, piece: Int, attempt: Int) {
        val inQueue = runCatching { s.handle.getDownloadQueue().any { it.pieceIndex() == piece } }.getOrDefault(false)
        if (!inQueue || attempt >= 24) {
            s.gate.markFlushed(piece)
            s.flushPolls.remove(piece)
            return
        }
        val delayMs = when (attempt) { 0 -> 20L; 1 -> 50L; else -> 100L }
        engineExec.schedule({ pollFlush(s, piece, attempt + 1) }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Readers wait until libtorrent reports exactly the file priorities we asked for (FILE_PRIO is
     * only a hint: checked here, polled every 50–100 ms, given up after ~5 s).
     */
    private fun releaseFileBarrier(s: ActiveStream, timedOut: Boolean, attempt: Int = 0) {
        if (s.fileReady || s.closedReason != null || streams[s.info.infoHash] !== s) return
        val intended = s.intendedPrios
        val inForce = intended == null || runCatching {
            s.handle.filePriorities().map { it.swig().toInt() } == intended
        }.getOrDefault(false)
        if (!inForce && !timedOut && attempt < 60) {
            if (attempt == 0 || s.barrierPolling.compareAndSet(false, true)) {
                engineExec.schedule({ s.barrierPolling.set(false); releaseFileBarrier(s, false, attempt + 1) }, if (attempt < 4) 50 else 100, TimeUnit.MILLISECONDS)
            }
            return
        }
        if (!inForce) EngineStats.event(s.info.infoHash, "barrierGiveUp", mapOf("tries" to attempt))
        s.fileReady = true
        s.forceFull = true
        s.gate.signalAll()
        kick(s, immediate = true)
    }

    private fun kick(s: ActiveStream, immediate: Boolean) {
        if (immediate) {
            runCatching { engineExec.execute { replan(s) } }
            return
        }
        if (s.pending.compareAndSet(false, true)) runCatching { engineExec.schedule({ replan(s) }, s.profile.replanThrottleMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * The zone scheduler (docs/STREAMING_V2.md §4): plan → merge → diff → apply, on the engine
     * thread. Urgent pieces get deadlines timed against the playhead; everything ahead comes in
     * file order through a sequential range; libtorrent only ever receives the differences.
     */
    private fun replan(s: ActiveStream) {
        s.pending.set(false)
        if (s.closedReason != null || streams[s.info.infoHash] !== s) return
        val now = EngineStats.now()
        val gate = s.gate
        val span = s.info.span
        val P = s.profile
        if (!s.startupDone && startupComplete(s, now)) s.startupDone = true
        maybeProbe(s)
        val phase = when {
            s.background -> StreamPhase.BACKGROUND
            !s.startupDone -> StreamPhase.STARTUP
            else -> StreamPhase.PLAYING
        }
        val views = s.readers.values.map { r ->
            ReaderView(r.id, r.kind, r.position, r.openedNanos / 1_000_000, r.lastProgressNanos / 1_000_000, r.blockedPiece, r.bytesRead)
        }.toMutableList()
        if (s.lastClosedAt > 0 && now - s.lastClosedAt < P.ghostCloseMs && views.none { it.kind == ReaderKind.IN_APP }) {
            views += ReaderView(-1, ReaderKind.IN_APP, s.lastClosedPos, s.lastClosedAt, s.lastClosedAt, ghost = true)
        }
        val rate = MediaRate.of(s.info.file.size, s.playerDurationMs, s.hintDurationMs)
        val baselineOf: (Int) -> Int = { q -> s.baseline.getOrElse(q) { 0 }.toInt() }
        val plan = ZoneScheduler.plan(
            PlanInput(span, gate.pieces, P, phase, s.download, views, s.lastAnchor, s.signals.view(now), rate, s.startupSet, s.startupDone, StorageAllowance(), baselineOf),
        )
        s.lastAnchor = plan.anchor
        if (phase == StreamPhase.STARTUP || EngineFlags.verboseStats) {
            // What the scheduler asks for, logged when it changes: urgent pieces, sequential range, sentinel.
            val dl = plan.deadlines.keys.sorted()
            val sig = "${dl.take(12)}|${plan.seq}|${plan.sentinel}"
            if (sig != s.lastPlanSig) {
                s.lastPlanSig = sig
                EngineStats.event(s.info.infoHash, "plan", mapOf("ph" to phase.name.lowercase(), "dl" to dl.take(12).joinToString(","), "n" to dl.size,
                    "seq" to plan.seq?.let { "${it.first}-${it.last}" }, "sent" to plan.sentinel, "readers" to views.joinToString(",") { "${it.kind.name.take(1)}${it.pos / span.pieceLength}" }))
            }
        }
        val merged = PlanMerge.merge(plan, span, emptyList(), baselineOf, gate.pieces, s.numPieces, streamMode = !s.download, prevGuard = s.guard,
            pieceLength = span.pieceLength, rate = rate, nowMs = now)
        s.guard = merged.guard
        val urgentBytes = maxOf(2L * span.pieceLength, minOf(maxOf(rate.bytesPerSec, 128 * KiB) * P.urgentSec, P.urgentMaxBytes))
        val (ops, next) = PlanDiff.diff(s.applied, merged,
            DiffContext(now, s.forceFull, s.forceClear, gate.pieces, P, s.numPieces, P.farSeekBytes(urgentBytes)))
        s.forceFull = false
        s.forceClear = false
        // While new file priorities are being applied, send deadlines only: piece priorities would be overwritten.
        val allowed = if (s.fileReady) ops else ops.filter { it !is PlanOp.SetPriorities && it !is PlanOp.SetAllPriorities }
        apply(s, allowed)
        s.applied = if (s.fileReady) next else next.copy(vector = s.applied?.vector)
        if (allowed.isNotEmpty()) s.lastPlanAt = now
    }

    private fun startupComplete(s: ActiveStream, now: Long): Boolean {
        if (s.signals.firstFrame) return true
        if (now - s.startedAt > s.profile.startupExitMs) return true
        val st = s.startupSet ?: return true
        val span = s.info.span
        val had = span.piecesForBytes(st.header.first, st.header.last + 1).all { s.gate.pieces.has(it) } &&
            (st.index?.let { ix -> span.piecesForBytes(ix.first, ix.last + 1).all { s.gate.pieces.has(it) } } ?: true)
        return had && s.readers.values.any { it.bytesRead >= 4L * span.pieceLength }
    }

    /** Once the header is on disk, find the real index (MP4 moov / MKV Cues) instead of a blind tail. */
    private fun maybeProbe(s: ActiveStream) {
        if (s.probed || s.probeAttempts >= 5) return
        val st = s.startupSet ?: return
        val span = s.info.span
        if (!span.piecesForBytes(st.header.first, st.header.last + 1).all { s.gate.isReadable(it) }) return
        s.probed = true
        s.probeAttempts++
        runCatching {
            io.execute {
                val probe = ContainerProbe(span.size, s.info.file.extension)
                val step = runCatching {
                    java.io.RandomAccessFile(s.info.path, "r").use { raf ->
                        fun read(pos: Long, len: Int): ByteArray? {
                            val end = minOf(span.size, pos + len)
                            if (pos >= end) return null
                            if (!span.piecesForBytes(pos, end).all { s.gate.isReadable(it) }) return null
                            val b = ByteArray((end - pos).toInt())
                            raf.seek(pos); raf.readFully(b)
                            return b
                        }
                        var st2 = probe.step(::read)
                        repeat(3) { if (st2 is ProbeStep.Need) st2 = probe.step(::read) }
                        st2
                    }
                }.getOrNull()
                engineExec.execute {
                    when (step) {
                        is ProbeStep.Done -> {
                            s.startupSet = StartupPlanner.refine(st, step.layout, span)
                            EngineStats.event(s.info.infoHash, "index", mapOf("kind" to step.layout.kind.name.lowercase(), "tail" to if (step.layout.needTail) 1 else 0))
                            kick(s, immediate = true)
                        }
                        else -> s.probed = false // bytes not there yet: try again on a later plan
                    }
                }
            }
        }
    }

    private fun apply(s: ActiveStream, ops: List<PlanOp>) {
        val h = s.handle
        for (op in ops) runCatching {
            when (op) {
                PlanOp.ClearDeadlines -> h.clearPieceDeadlines()
                is PlanOp.SetDeadline -> h.setPieceDeadline(op.piece, op.ms)
                is PlanOp.ResetDeadline -> h.resetPieceDeadline(op.piece)
                is PlanOp.SetPriorities -> {
                    val v = org.libtorrent4j.swig.int_byte_pair_vector(op.changes.map { org.libtorrent4j.swig.int_byte_pair(it.first, it.second.toByte()) })
                    h.swig().prioritize_pieces_ex(v)
                    runCatching { v.delete() }
                }
                is PlanOp.SetAllPriorities -> h.prioritizePieces(Array(op.vector.size) { Priority.fromSwig(op.vector[it].toInt()) })
                is PlanOp.SetSequential -> {
                    if (!s.seqFlagSet) {
                        h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                        s.seqFlagSet = true
                        EngineStats.event(s.info.infoHash, "seq", mapOf("on" to if (h.getFlags().and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero()) 1 else 0))
                    }
                    h.setSequentialRange(op.first, op.last)
                }
                PlanOp.UnsetSequential -> {
                    h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    s.seqFlagSet = false
                }
            }
        }
    }

    /**
     * Stream over: drop its deadlines, stop sequential mode and (unless a new stream on the same
     * torrent is taking over, whose file priorities decide) put every piece back at its file's priority.
     */
    private fun clearPlan(s: ActiveStream, restorePriorities: Boolean = true) {
        val h = s.handle
        runCatching { h.clearPieceDeadlines() }
        if (restorePriorities) runCatching { h.prioritizePieces(Array(s.baseline.size) { Priority.fromSwig(s.baseline[it].toInt()) }) }
        runCatching { h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        s.seqFlagSet = false
        s.applied = null
    }

    /** Per piece: the highest priority among the files it belongs to (pad files excluded). */
    private fun baselineVector(fs: org.libtorrent4j.FileStorage, numPieces: Int, pieceLength: Int, filePrios: List<Int>): ByteArray {
        val v = ByteArray(numPieces)
        for (f in 0 until fs.numFiles()) {
            val prio = filePrios.getOrElse(f) { 4 }
            if (prio <= 0 || fs.padFileAt(f) || fs.fileSize(f) == 0L) continue
            val firstP = (fs.fileOffset(f) / pieceLength).toInt()
            val lastP = ((fs.fileOffset(f) + fs.fileSize(f) - 1) / pieceLength).toInt().coerceAtMost(numPieces - 1)
            for (p in firstP..lastP) if (v[p] < prio) v[p] = prio.toByte()
        }
        return v
    }

    /** One gate per torrent, created once metadata is known and seeded from libtorrent's bitfield. */
    private fun ensureGate(key: String, h: TorrentHandle, numPieces: Int): PieceGate {
        gates[key]?.takeIf { it.numPieces == numPieces && !it.isClosed }?.let { return it }
        val gate = PieceGate(numPieces, needsFlush = disk == DiskBackend.MMAP)
        gates[key] = gate
        seed(gate, h)
        preGate.remove(key)?.forEach { gate.markVerified(it) }
        gate.signalAll()
        return gate
    }

    private fun seed(gate: PieceGate, h: TorrentHandle) {
        runCatching {
            val bf = h.status(TorrentHandle.QUERY_PIECES).pieces()
            val n = minOf(bf.size(), gate.numPieces)
            for (p in 0 until n) if (bf.getBit(p)) gate.seedVerified(p)
        }
        gate.signalAll()
    }

    /**
     * Waits for metadata. The deferred is registered before checking, re-checked every second with
     * a fresh (uncached) status, and never removed by a waiter, so a late or concurrent alert can't
     * be missed (which used to turn a quick metadata into a 90 s "No one is sharing").
     */
    private suspend fun awaitMetadata(hash: String, h: TorrentHandle, timeoutMs: Long) {
        val waiter = metadataWaiters.getOrPut(hash) { CompletableDeferred() }
        fun has() = runCatching { h.status(true).hasMetadata() }.getOrDefault(false)
        if (has()) return
        try {
            withTimeout(timeoutMs) {
                while (!has()) withTimeoutOrNull(1_000) { waiter.await() }
            }
        } catch (t: TimeoutCancellationException) {
            throw EngineException("No one is sharing this right now. Try another source.")
        }
    }

    /**
     * Deletes the oldest unused streams until the cache fits its budget (sized for this device).
     * The titles stopped most recently are kept unless the disk is critically full; [budgetBytes]
     * of 0 means "clear everything not in use" (the user's Clear button).
     */
    fun trimCache(budgetBytes: Long) {
        val explicit = budgetBytes == 0L
        val freeBytes = runCatching { StatFs(streamDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val entries = metas.values.filter { it.mode == TorrentMode.STREAM }.map { m ->
            CacheEntry(m.infoHash, streamBytes(m.infoHash), m.lastUsedAt, m.lastStoppedAt,
                claimed = streams.containsKey(m.infoHash) || starting.containsKey(m.infoHash))
        }
        val cache = entries.sumOf { it.bytes }
        val budget = if (explicit) 0 else CachePolicy.budget(formFactor, freeBytes, cache, cacheLimitBytes())
        val evict = CachePolicy.evict(entries, budget, freeBytes, CachePolicy.hardFloor(formFactor), explicit)
        evict.forEach { key ->
            synchronized(streamLock) {
                // A player may have started this torrent again since the list was made.
                if (!streams.containsKey(key) && !starting.containsKey(key)) remove(key, deleteFiles = true)
            }
        }
        if (evict.isNotEmpty()) Log.i(TAG, "cache trimmed ${evict.size} (budget ${budget shr 20} MiB, free ${freeBytes shr 20} MiB)")
    }

    /** Settings → Storage → "Stream cache limit" (the data session's AutoDownloadSettings); null = automatic (§8.3 C). */
    private fun cacheLimitBytes(): Long? =
        runCatching { (context.applicationContext as? com.kirtanya.reel.ReelApp)?.autoDownloader?.settings?.value?.cacheLimitGb }.getOrNull()?.let { it * GiB }

    /** Bytes held by stream-mode torrents (downloaded pieces, not sparse file lengths). */
    fun cacheBytes(): Long = metas.values.filter { it.mode == TorrentMode.STREAM }.sumOf { streamBytes(it.infoHash) }

    /** Everything on disk counts against the cache, including pieces of files no longer wanted. */
    private fun streamBytes(hash: String): Long = find(hash)?.status()?.totalDone() ?: 0L

    // ---------------------------------------------------------------- download rules (§8.3)

    private fun startRules() {
        // A settings change applies at once (and moves the alarm to the new edge).
        scope.launch { rulesStore.rules.collect { kickRules() } }
        (context.applicationContext as? com.kirtanya.reel.ReelApp)?.let { app ->
            scope.launch(Dispatchers.IO) {
                app.autoDownloader.settings.map { it.cacheLimitGb }.distinctUntilChanged().drop(1).collect { runCatching { trimCache(DEFAULT_CACHE_BUDGET) } }
            }
        }
        // Wrapped: an exception would cancel a fixed-delay task for good.
        engineExec.scheduleWithFixedDelay({ runCatching { evaluateNight() }.onFailure { Log.w(TAG, "night rule failed", it) } }, 30, 30, TimeUnit.SECONDS)
        engineExec.scheduleWithFixedDelay({ runCatching { govern() }.onFailure { Log.w(TAG, "governor failed", it) } }, 2, 2, TimeUnit.SECONDS)
    }

    /** Re-runs the overnight window on the engine thread (EngineService calls this from the window-edge alarm). */
    fun evaluateRules() = kickRules()

    private fun kickRules() {
        if (!started) return
        runCatching { engineExec.execute { runCatching { evaluateNight() }.onFailure { Log.w(TAG, "night rule failed", it) } } }
    }

    private fun minuteNow(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

    private fun claimed(hash: String) = streams.containsKey(hash) || starting.containsKey(hash)

    /** Does the overnight window keep [meta]'s torrent waiting right now? */
    private fun nightHolds(meta: TorrentMeta, h: TorrentHandle): Boolean {
        if (meta.mode != TorrentMode.DOWNLOAD || meta.startNow) return false
        val finished = runCatching { h.status().isFinished }.getOrDefault(false)
        return NightRule.holds(rulesStore.rules.value.night, minuteNow(), download = true, startNow = false, finished = finished, claimed = claimed(meta.infoHash))
    }

    /**
     * Holds DOWNLOAD torrents outside the overnight window and releases them inside it (§8.3 A). Never touches a
     * claimed (streamed) torrent, the user's own pause, or what the home disk's P2P rule holds. Engine thread.
     */
    private fun evaluateNight() {
        val night = rulesStore.rules.value.night
        var held = 0
        var released = 0
        var fetching = false
        for (meta in metas.values.toList()) {
            if (meta.mode != TorrentMode.DOWNLOAD) continue
            val key = meta.infoHash
            if (claimed(key)) continue
            val h = find(key) ?: continue
            val hold = nightHolds(meta, h)
            val paused = h.getFlags().and_(TorrentFlags.PAUSED).non_zero()
            when {
                hold && !meta.pausedForNight -> {
                    // Already stopped for its own reason: Resume will find the window closed.
                    if (meta.userPaused || meta.pausedForSpace || meta.seedingDone) continue
                    h.unsetFlags(TorrentFlags.AUTO_MANAGED)
                    h.pause()
                    saveMeta(meta.copy(pausedForNight = true))
                    held++
                }
                // Running again behind our back (a stream on it ended, the queue): back to waiting.
                hold && !paused && !meta.userPaused -> { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause() }
                !hold && meta.pausedForNight -> {
                    saveMeta(meta.copy(pausedForNight = false))
                    // The P2P rule's own hook: true = it holds the download (and resumes it on an allowed network).
                    if (!meta.userPaused && !downloadHold(key)) {
                        h.setFlags(TorrentFlags.AUTO_MANAGED)
                        h.resume()
                    }
                    released++
                }
            }
            if (!hold && !meta.userPaused && !paused) {
                val st = runCatching { h.status() }.getOrNull()
                if (st != null && !st.isFinished) fetching = true
            }
        }
        val win = NightRule.hourLabel(night.startHour).take(2) + "-" + NightRule.hourLabel(night.endHour).take(2)
        if (held > 0) EngineStats.log("night", mapOf("state" to "hold", "n" to held, "win" to win))
        if (released > 0) EngineStats.log("night", mapOf("state" to "release", "n" to released, "win" to win))
        if (held + released > 0) refreshSnapshotsSoon()
        nightAlarm.schedule(NightRule.nextEdge(night, java.time.ZonedDateTime.now())?.toInstant()?.toEpochMilli())
        // The CPU stays up only for downloads inside an open window (a phone on the charger overnight).
        val wake = night.enabled && NightRule.inWindow(night, minuteNow()) && fetching
        if (wake || nightAlarm.held) nightAlarm.hold(wake)
    }

    /** A stream someone is watching right now: playing or buffering in the app, starting, or read by another player. */
    private fun isWatching(s: ActiveStream, now: Long): Boolean {
        if (s.closedReason != null || s.background) return false
        if (!s.startupDone) return true
        // playWhenReady, not isPlaying: a rebuffering player needs the bandwidth most.
        if (s.signals.fresh(now) && s.signals.playWhenReady) return true
        if (s.readers.values.any { it.kind == ReaderKind.HTTP }) return true
        return System.currentTimeMillis() - s.lastHttpActivityAt < 10_000
    }

    /** Speed cap while watching (§8.3 B), every 2 s on the engine thread. */
    private fun govern() {
        val now = EngineStats.now()
        val choice = if (EngineFlags.govern) rulesStore.rules.value.watchCap else WatchCap.OFF
        val watching = streams.values.any { isWatching(it, now) }
        val list = _torrents.value
        val targets = list.filter { it.mode == TorrentMode.DOWNLOAD && it.phase in CAPPABLE && !claimed(it.infoHash) }
        val fetching = targets.count { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA }
        val d = governor.tick(now, watching, list.sumOf { it.downBps.toLong() }, choice, fetching, targets.size)
        val want = if (d.capping) targets.associate { it.infoHash to (d.perTorrentDown to d.perTorrentUp) } else emptyMap()
        // Lift first: the cap ended, or the torrent is now played / paused / gone.
        for (key in capped.keys - want.keys) find(key)?.let { h -> runCatching { h.setDownloadLimit(0); h.setUploadLimit(0) } }
        capped.keys.retainAll(want.keys)
        for ((key, lim) in want) {
            val old = capped[key]
            if (old != null && !WatchGovernor.changedEnough(old.first, lim.first) && old.second == lim.second) continue
            val h = find(key) ?: continue
            runCatching { h.setDownloadLimit(lim.first.toInt()); h.setUploadLimit(lim.second.toInt()) }
            capped[key] = lim
        }
        cappedKeys = capped.keys.toSet()
        val mode = when {
            d.capping && !capping -> "cap"
            !d.capping && capping -> "lift"
            d.capping && WatchGovernor.changedEnough(loggedPer, d.perTorrentDown) -> "adjust"
            else -> null
        }
        if (mode != null) {
            EngineStats.log("gov", mapOf("mode" to mode, "choice" to choice.name.lowercase(), "cap" to d.totalDown, "per" to d.perTorrentDown,
                "up" to d.perTorrentUp, "n" to targets.size, "link" to d.linkPeak))
            loggedPer = d.perTorrentDown
        }
        capping = d.capping
    }

    private fun refreshSnapshotsSoon() {
        scope.launch { runCatching { refreshSnapshots() } }
    }

    // ---------------------------------------------------------------- alerts + polling

    private val listener = object : AlertListener {
        // Only what we handle: everything else is never wrapped into a Java object.
        override fun types(): IntArray = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.PERFORMANCE.swig(),
            AlertType.TORRENT_CHECKED.swig(),
            AlertType.FILE_PRIO.swig(),
            AlertType.STORAGE_MOVED.swig(),
            AlertType.STORAGE_MOVED_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            try {
                handleAlert(alert)
            } catch (t: Throwable) {
                Log.w(TAG, "alert ${alert.type()} failed", t)
            }
        }
    }

    private fun handleAlert(alert: Alert<*>) {
        when (alert.type()) {
            AlertType.PIECE_FINISHED -> {
                val a = alert as PieceFinishedAlert
                val key = a.handle().key()
                val piece = a.pieceIndex()
                val gate = gates[key]
                if (gate != null) gate.markVerified(piece)
                else preGate.getOrPut(key) { java.util.concurrent.ConcurrentLinkedQueue() }.let { q -> if (q.size < 4096) q.add(piece) }
                EngineStats.of(key)?.let { s -> if (s.firstPieceAt == null) { s.firstPiece(EngineStats.now()); EngineStats.event(key, "piece1", mapOf("p" to piece)) } }
            }
            AlertType.METADATA_RECEIVED -> {
                val h = (alert as TorrentAlert<*>).handle()
                val key = h.key()
                metadataWaiters[key]?.complete(Unit)
                applyFileIntent(h)
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
            AlertType.SAVE_RESUME_DATA -> {
                val a = alert as SaveResumeDataAlert
                val key = a.handle().key()
                // Copy out now: the alert's native memory is only valid inside this callback.
                val bytes = AddTorrentParams.writeResumeDataBuf(a.params())
                val write = Runnable {
                    if (metas.containsKey(key)) atomicWrite(File(stateDir, "$key.resume"), bytes)
                    countDownFinal(key)
                }
                // During shutdown the writer may already be draining: write inline then.
                runCatching { io.execute(write) }.onFailure { write.run() }
            }
            AlertType.SAVE_RESUME_DATA_FAILED -> countDownFinal((alert as TorrentAlert<*>).handle().key())
            AlertType.TORRENT_FINISHED -> (alert as TorrentAlert<*>).handle().saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            AlertType.TORRENT_CHECKED -> {
                // Pieces loaded from resume data: make sure the gate knows every one.
                val h = (alert as TorrentAlert<*>).handle()
                val key = h.key()
                gates[key]?.let { g -> engineExec.execute { seed(g, h) } }
            }
            AlertType.FILE_PRIO -> {
                val key = (alert as TorrentAlert<*>).handle().key()
                streams[key]?.let { st -> engineExec.execute { releaseFileBarrier(st, timedOut = false) } }
            }
            AlertType.STORAGE_MOVED -> {
                val h = (alert as TorrentAlert<*>).handle()
                metas[h.key()]?.let { saveMeta(it.copy(savePath = h.savePath())) }
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
            AlertType.STORAGE_MOVED_FAILED -> {
                // A file of that name is already in Downloads: keep downloading where the pieces are.
                val h = (alert as TorrentAlert<*>).handle()
                val key = h.key()
                errors[key] = "Couldn't move to Downloads (a file with that name is already there)"
                metas[key]?.let { saveMeta(it.copy(savePath = h.savePath())) }
            }
            AlertType.TORRENT_ERROR -> {
                val a = alert as TorrentErrorAlert
                errors[a.handle().key()] = a.error().message
            }
            AlertType.FILE_ERROR -> {
                val a = alert as FileErrorAlert
                errors[a.handle().key()] = a.error().message
            }
            AlertType.PERFORMANCE -> EngineStats.log("perf", mapOf("code" to (alert as PerformanceAlert).warningCode))
            else -> Unit
        }
    }

    private fun countDownFinal(key: String) {
        val pending = pendingFinal ?: return
        if (pending.remove(key)) finalLatch?.countDown()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            runCatching { refreshSnapshots() }.onFailure { Log.w(TAG, "poll failed", it) }
            // Deadlines follow the playhead, and startup can end on time alone: replan every second.
            streams.values.forEach { kick(it, immediate = false) }
            delay(1000)
        }
    }

    private suspend fun resumeSaveLoop() {
        var ticks = 0
        while (scope.isActive) {
            delay(if (formFactor == FormFactor.TV) 30_000 else 60_000)
            runCatching {
                handles.torrents().filter { it.isValid && it.needSaveResumeData() }
                    .forEach { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.ONLY_IF_MODIFIED)) }
            }
            // The DHT table every ~10 min: the TV process is often killed without a shutdown.
            if (++ticks % (if (formFactor == FormFactor.TV) 20 else 10) == 0) saveSessionState()
        }
    }

    private fun refreshSnapshots() {
        _torrents.value = handles.torrents().filter { it.isValid }.mapNotNull { h ->
            val hash = h.key()
            val meta = metas[hash] ?: return@mapNotNull null
            val st = h.status()
            val phase = phaseOf(h, st)
            val stalled = if (phase == TorrentPhase.DOWNLOADING && st.downloadPayloadRate() == 0) {
                stalls.merge(hash, 1, Int::plus) ?: 1
            } else {
                stalls[hash] = 0
                0
            }
            TorrentSnapshot(
                infoHash = hash,
                name = st.name().ifBlank { h.getName() }.ifBlank { hash.take(8) },
                mode = meta.mode,
                phase = phase,
                progress = st.progress(),
                wantedBytes = st.totalWanted(),
                doneBytes = st.totalWantedDone(),
                downBps = st.downloadPayloadRate(),
                upBps = st.uploadPayloadRate(),
                peers = st.numPeers(),
                seeds = st.numSeeds(),
                hasMetadata = st.hasMetadata(),
                savePath = h.savePath(),
                error = errors[hash],
                stalledForSec = stalled,
                fileIndex = meta.streamFileIndex ?: meta.wantedFiles.singleOrNull(),
                userPaused = meta.userPaused,
                streaming = streams.containsKey(hash),
                waitingForNight = meta.pausedForNight && !meta.userPaused,
                notice = when {
                    meta.pausedForNight && !meta.userPaused -> NightRule.waitingLabel(rulesStore.rules.value.night)
                    hash in cappedKeys -> "Slowed while you watch"
                    else -> null
                },
            )
        }.sortedByDescending { metas[it.infoHash]?.addedAt ?: 0 }
    }

    private fun phaseOf(h: TorrentHandle, st: TorrentStatus): TorrentPhase = when {
        st.errorCode().isError -> TorrentPhase.ERROR
        h.getFlags().and_(TorrentFlags.PAUSED).non_zero() -> TorrentPhase.PAUSED
        else -> when (st.state()) {
            TorrentStatus.State.DOWNLOADING_METADATA -> TorrentPhase.METADATA
            TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentPhase.CHECKING
            TorrentStatus.State.FINISHED -> TorrentPhase.FINISHED
            TorrentStatus.State.SEEDING -> TorrentPhase.SEEDING
            else -> TorrentPhase.DOWNLOADING
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun saveMeta(meta: TorrentMeta) {
        metas[meta.infoHash] = meta
        val text = json.encodeToString(TorrentMeta.serializer(), meta)
        runCatching { io.execute { atomicWrite(File(stateDir, "${meta.infoHash}.json"), text.toByteArray()) } }
    }

    /** Write-then-rename, so a kill mid-write never leaves a truncated file. */
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val af = AtomicFile(file)
        val out = runCatching { af.startWrite() }.getOrElse { Log.w(TAG, "write ${file.name} failed", it); return }
        try {
            out.write(bytes)
            af.finishWrite(out)
        } catch (t: Throwable) {
            af.failWrite(out)
            Log.w(TAG, "write ${file.name} failed", t)
        }
    }

    private fun readAtomic(file: File): ByteArray? = runCatching { AtomicFile(file).readFully() }.getOrNull()

    /**
     * Re-adds every saved torrent, oldest first (queue order), with the flags [RestorePolicy] decides:
     * user-paused downloads stay paused, streams come back paused. Saved per-torrent limits and
     * leftover piece priorities are reset. A torrent with missing or damaged resume data comes back
     * from its bare magnet instead of disappearing.
     */
    private fun restore() {
        val loaded = stateDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            runCatching { json.decodeFromString(TorrentMeta.serializer(), String(readAtomic(file) ?: return@mapNotNull null)).migrated() }
                .onFailure { Log.w(TAG, "meta ${file.name} unreadable", it) }.getOrNull()
        }.sortedBy { it.addedAt }
        val night = rulesStore.rules.value.night
        val minute = minuteNow()
        for (meta0 in loaded) {
            runCatching {
                var meta = meta0
                val raw = readAtomic(File(stateDir, "${meta.infoHash}.resume"))?.let { bytes ->
                    val ec = error_code()
                    libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), ec).takeIf { ec.value() == 0 }
                }
                val params = if (raw != null) {
                    AddTorrentParams(raw).also { p ->
                        // Hybrid torrents used to be saved under their v2 hash: move them to the canonical key.
                        val key = runCatching { keyOf(p.getInfoHashes()) }.getOrNull()
                        if (key != null && key != meta.infoHash) meta = migrateKey(meta, key, p.getSavePath())
                    }
                } else {
                    Log.w(TAG, "resume data for ${meta.infoHash.take(8)} missing; re-adding from its magnet")
                    AddTorrentParams.parseMagnetUri("magnet:?xt=urn:btih:${meta.infoHash}").also { p ->
                        p.setTrackers(DEFAULT_TRACKERS)
                        p.setSavePath(meta.savePath ?: saveDirFor(meta.infoHash, meta.mode).absolutePath)
                        if (meta.mode == TorrentMode.DOWNLOAD) meta = meta.copy(pendingFileIntent = true)
                    }
                }
                val saved = params.getFlags()
                // Finished downloads aren't known yet (no status before add): the first rules run releases those.
                val nightHold = NightRule.holds(night, minute, meta.mode == TorrentMode.DOWNLOAD, meta.startNow, finished = false, claimed = false)
                val decided = RestorePolicy.restore(
                    RestoreFlags(
                        autoManaged = saved.and_(TorrentFlags.AUTO_MANAGED).non_zero(),
                        paused = saved.and_(TorrentFlags.PAUSED).non_zero(),
                        sequential = saved.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero(),
                    ),
                    RestoreMeta(meta.mode == TorrentMode.DOWNLOAD, meta.userPaused, meta.seedingDone, meta.pausedForSpace, meta.pausedForStream, meta.wasAutoManaged,
                        pausedForNight = meta.pausedForNight, nightHold = nightHold),
                )
                var flags = saved.without(TorrentFlags.AUTO_MANAGED).without(TorrentFlags.PAUSED).without(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                if (decided.autoManaged) flags = flags.or_(TorrentFlags.AUTO_MANAGED)
                if (decided.paused) flags = flags.or_(TorrentFlags.PAUSED)
                // Home disk P2P rule (§5.3): a download restored on a network where it must wait comes back paused.
                else if (meta.mode == TorrentMode.DOWNLOAD && downloadHold(meta.infoHash)) flags = flags.without(TorrentFlags.AUTO_MANAGED).or_(TorrentFlags.PAUSED)
                if (raw == null && (meta.mode == TorrentMode.STREAM || !meta.intent.fullTorrent)) flags = flags.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
                params.setFlags(flags)
                // libtorrent saves these in resume data: never let an old throttle or cap come back.
                params.setDownloadLimit(0)
                params.setUploadLimit(0)
                params.setMaxConnections(-1)
                params.piecePriorities(arrayOf<Priority>())
                val heldForNight = nightHold && !meta.userPaused && !meta.seedingDone && !meta.pausedForSpace
                meta = meta.copy(pausedForStream = false, pausedForNight = heldForNight, wasAutoManaged = null, savePath = meta.savePath ?: params.getSavePath())
                metas[meta.infoHash] = meta
                if (meta != meta0) saveMeta(meta)
                metadataWaiters.getOrPut(meta.infoHash) { CompletableDeferred() }
                val handle = handles.addTorrent(params, ErrorCode(error_code()))
                if (!handle.isValid) metas.remove(meta.infoHash)
            }.onFailure { Log.w(TAG, "restore ${meta0.infoHash.take(8)} failed", it) }
        }
    }

    private fun migrateKey(meta: TorrentMeta, key: String, savePath: String?): TorrentMeta {
        Log.i(TAG, "migrating ${meta.infoHash.take(8)} → ${key.take(8)}")
        File(stateDir, "${meta.infoHash}.resume").renameTo(File(stateDir, "$key.resume"))
        File(stateDir, "${meta.infoHash}.json").delete()
        return meta.copy(infoHash = key, savePath = meta.savePath ?: savePath)
    }

    private fun find(hash: String): TorrentHandle? =
        runCatching { handles.findTorrent(Sha1Hash.parseHex(hash)) }.getOrNull()?.takeIf { it.isValid }

    private class ActiveStream(
        val info: StreamInfo,
        val handle: TorrentHandle,
        val gate: PieceGate,
        val profile: SchedulerProfile,
        val numPieces: Int,
        /** Per piece: priority from the torrent's file choices (restored when the stream stops). */
        val baseline: ByteArray,
        val download: Boolean,
    ) {
        /** Open readers (in-app player, external players, bench). */
        val readers = ConcurrentHashMap<Long, StreamReaderCore>()
        @Volatile var lastActivityAt: Long = System.currentTimeMillis()
        /** Last time an external player opened or closed a request (0 = never). */
        @Volatile var lastHttpActivityAt: Long = 0
        @Volatile var lastPlanSig: String? = null
        /** File priorities this stream asked for; readers wait until libtorrent reports them. */
        @Volatile var intendedPrios: List<Int>? = null
        val barrierPolling = java.util.concurrent.atomic.AtomicBoolean(false)
        /** A download's file this stream raised from IGNORE (lowered again when the stream stops). */
        @Volatile var raisedFile: Int? = null
        /** Set when the stream stops or is superseded: every reader fails with it. */
        @Volatile var closedReason: java.io.IOException? = null
        /** False while new file priorities are being applied (pieces may still be in the part file). */
        @Volatile var fileReady = true
        /** Media bytes per second (from the player's duration) for read-ahead sizing. */
        @Volatile var bytesPerSec: Long? = null
        @Volatile var playerDurationMs: Long? = null
        @Volatile var hintDurationMs: Long? = null
        @Volatile var lastClosedPos = 0L
        @Volatile var lastClosedAt = 0L
        @Volatile var lastPlanAt = 0L
        @Volatile var background = false
        @Volatile var startupSet: StartupSet? = null
        @Volatile var startupDone = false
        @Volatile var probed = false
        @Volatile var probeAttempts = 0
        @Volatile var forceFull = false
        @Volatile var forceClear = false
        val startedAt = EngineStats.now()
        val signals = PlayerSignals()
        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
        val flushPolls: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        // Engine thread only.
        var lastAnchor: Long? = null
        var applied: AppliedState? = null
        var guard: GuardState? = null
        var seqFlagSet = false
    }

    companion object {
        private const val TAG = "ReelEngine"
        private const val METADATA_TIMEOUT_MS = 90_000L
        /** A closed reader's window is kept this long (ExoPlayer reopens after extractor seeks). */
        private const val GHOST_MS = 1_500L
        /** Only a marker now: the real budget comes from [CachePolicy] for this device. */
        const val DEFAULT_CACHE_BUDGET = -1L
        /** Phases the watch cap applies to: everything that can move bytes. */
        private val CAPPABLE = setOf(TorrentPhase.DOWNLOADING, TorrentPhase.METADATA, TorrentPhase.FINISHED, TorrentPhase.SEEDING)

        private fun is64Bit() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && android.os.Process.is64Bit()

        /**
         * Home disk P2P rule hook (docs/HOME_DISK.md §5.3), set by the phone's P2pGate before the engine starts:
         * true = this download must wait on the current network, so it is added / restored paused.
         */
        @Volatile var downloadHold: (String) -> Boolean = { false }

        /** The canonical key ([TorrentKey]) of a libtorrent info-hash pair. */
        fun keyOf(ih: InfoHash): String = TorrentKey.canonical(if (ih.hasV1()) ih.v1.toHex() else null, ih.best.toHex())
    }
}

/** The torrent's canonical key: v1 hex when it has one, so hybrid torrents don't change key at metadata. */
private fun TorrentHandle.key(): String = TorrentEngine.keyOf(InfoHash(swig().info_hashes()))

private fun torrent_flags_t.without(flag: torrent_flags_t): torrent_flags_t =
    if (and_(flag).non_zero()) xor_(flag) else this
