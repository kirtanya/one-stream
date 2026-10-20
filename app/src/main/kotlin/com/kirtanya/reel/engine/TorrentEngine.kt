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
import com.kirtanya.reel.core.stream.DiskBackend
import com.kirtanya.reel.core.stream.FileChoice
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.PieceDeadline
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
import com.kirtanya.reel.core.stream.StreamPlanner
import com.kirtanya.reel.core.stream.TorrentFileEntry
import com.kirtanya.reel.core.stream.TorrentKey
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
    private val filePrioIssued = ConcurrentHashMap<String, Int>()
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
        if (mode == TorrentMode.STREAM && streamFileIdx != null && streamFileIdx >= 0) {
            // Fetch nothing but the chosen file: without this, an abandoned start (Back during
            // "Finding peers…") downloaded the whole torrent once metadata arrived.
            flags = flags.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            params.filePriorities(Array(streamFileIdx + 1) { if (it == streamFileIdx) Priority.DEFAULT else Priority.IGNORE })
        }
        params.setFlags(flags)
        val intent = FileChoice.merge(com.kirtanya.reel.core.stream.FileIntent(), onlyFileIndex, onlyFileHint)
        val meta = TorrentMeta(hash, mode, schema = 2, savePath = save.absolutePath, createdBy = if (mode == TorrentMode.STREAM) "stream" else "user")
            .let { if (mode == TorrentMode.DOWNLOAD) it.withIntent(intent) else it }
            .copy(pendingFileIntent = mode == TorrentMode.DOWNLOAD && !intent.fullTorrent)
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
        if (metas[hash]?.mode == TorrentMode.DOWNLOAD) {
            // A queued download the user asks for goes to the front of the queue.
            if (h.getFlags().and_(TorrentFlags.AUTO_MANAGED).non_zero() && h.getFlags().and_(TorrentFlags.PAUSED).non_zero()) h.queuePositionTop()
            h.setFlags(TorrentFlags.AUTO_MANAGED)
        }
        h.resume()
        metas[hash]?.let { saveMeta(it.copy(userPaused = false, pausedForSpace = false)) }
    }

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = find(hash)
        streams.remove(hash)?.let { release(it, StreamGoneException("This stream was removed")) }
        gates.remove(hash)?.close(StreamGoneException("This stream was removed"))
        preGate.remove(hash)
        metadataWaiters.remove(hash)?.cancel()
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

    /** "Keep": a stream becomes a normal download in the Downloads folder; fetched pieces are reused. */
    fun keep(hash: String) {
        val h = find(hash) ?: return
        val meta = metas[hash] ?: return
        if (meta.mode == TorrentMode.DOWNLOAD) return
        keepInternal(h, meta, com.kirtanya.reel.core.stream.FileIntent())
    }

    private fun keepInternal(h: TorrentHandle, meta: TorrentMeta, intent: com.kirtanya.reel.core.stream.FileIntent) {
        h.moveStorage(downloadsDir().absolutePath)
        h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
        // A stream added for one file downloads nothing else: say explicitly what Keep wants.
        val keepIntent = if (intent.fullTorrent || (intent.wantedFiles.isEmpty() && intent.wantedHints.isEmpty())) intent.copy(fullTorrent = true) else intent
        saveMeta(meta.copy(mode = TorrentMode.DOWNLOAD, savePath = downloadsDir().absolutePath, pendingFileIntent = true).withIntent(keepIntent))
        applyFileIntent(h)
    }

    fun files(hash: String): List<TorrentFileEntry> {
        val info = find(hash)?.torrentFile() ?: return emptyList()
        val fs = info.files()
        return (0 until fs.numFiles())
            .filterNot { fs.padFileAt(it) }
            .map { TorrentFileEntry(it, fs.filePath(it), fs.fileSize(it)) }
    }

    fun snapshot(hash: String): TorrentSnapshot? = _torrents.value.firstOrNull { it.infoHash == hash }

    // ---------------------------------------------------------------- streaming

    /**
     * Waits for metadata, picks the file, focuses the torrent on it and queues the startup pieces.
     * Readers can open [StreamInfo.uri] (in-app) or [httpUrlFor] (external players) once this returns.
     */
    suspend fun prepareStream(hash: String, fileIdx: Int? = null, filenameHint: String? = null): StreamInfo =
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
            if (changed) {
                filePrioIssued.merge(hash, 1, Int::plus)
                handle.prioritizeFiles(Array(intended.size) { Priority.fromSwig(intended[it]) })
            }
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
            val active = ActiveStream(stream, handle, StreamPlanner(span), gate)
            active.fileReady = !changed
            synchronized(streamLock) {
                // A previous stream on this torrent (the last episode of a season pack): its readers
                // fail fast; the torrent and its gate carry on for the new one.
                streams[hash]?.let { old -> supersede(old) }
                // Register and resume together: a player stopping at the same moment can't pause us after.
                streams[hash] = active
                handle.resume()
            }
            if (changed) engineExec.schedule({ releaseFileBarrier(active, timedOut = true) }, 1_500, TimeUnit.MILLISECONDS)
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
        try {
            val cached = find(hash) != null
            EngineStats.begin(hash, tapMs, mapOf("dev" to formFactor.name.lowercase(), "disk" to disk.name.lowercase(), "warm" to if (cached) "cached" else "cold", "build" to "p2", "flags" to EngineFlags.summary()))
            created = addMagnetInternal(magnet, TorrentMode.STREAM, null, null, streamFileIdx = fileIdx).second
            find(hash)?.let { h ->
                runCatching { h.forceDHTAnnounce() }
                // A cached stream that was paused: peers are long gone, ask everyone again now.
                if (cached) runCatching { h.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL) }
            }
            return prepareStream(hash, fileIdx, filenameHint).also { prepared = true }
        } finally {
            withContext(NonCancellable) {
                starting.computeIfPresent(hash) { _, n -> (n - 1).takeIf { it > 0 } }
                if (!prepared) abandon(hash, created)
            }
        }
    }

    /** A start that didn't complete: drop what it added, or park it, unless someone else uses it. */
    private fun abandon(hash: String, created: Boolean) = synchronized(streamLock) {
        if (starting.containsKey(hash) || streams.containsKey(hash)) return@synchronized
        val meta = metas[hash] ?: return@synchronized
        if (meta.mode != TorrentMode.STREAM) return@synchronized
        val h = find(hash) ?: return@synchronized
        val done = runCatching { h.status().totalDone() }.getOrDefault(0L)
        EngineStats.end(hash, mapOf("abandoned" to 1))
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
        EngineStats.event(owner.infoHash, "rate", mapOf("B" to rate, "src" to "player"))
        kick(s, immediate = false)
    }

    /**
     * The player went to the background: stop fetching [owner]'s stream-mode torrent so it doesn't
     * eat bandwidth (e.g. while another app plays). Downloads and other players' streams are untouched.
     */
    fun suspendStream(hash: String, owner: StreamInfo?) = streamHandleIfOwned(hash, owner)?.pause()

    /** The player is visible again: carry on fetching. */
    fun resumeStream(hash: String, owner: StreamInfo?) = streamHandleIfOwned(hash, owner)?.resume()

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
            val h = find(hash) ?: return
            // Another player is starting this torrent (next episode of a season pack): leave it running.
            if (starting.containsKey(hash) || streams.containsKey(hash)) return
            if (metas[hash]?.mode == TorrentMode.STREAM) {
                h.pause()
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
        }
        Log.i(TAG, "stream stopped ${stream.info.file.name}")
        EngineStats.end(hash)
        // The first stop after a while also refreshes the saved DHT table.
        if (System.currentTimeMillis() - lastStateSaveAt > 60_000) saveSessionState()
        scope.launch(Dispatchers.IO) { runCatching { trimCache(DEFAULT_CACHE_BUDGET) } }
    }

    /** Readers of [old] fail fast; its deadlines are cleared (the torrent and gate carry on). */
    private fun supersede(old: ActiveStream) = release(old, StreamGoneException("superseded"))

    private fun release(s: ActiveStream, reason: java.io.IOException) {
        s.closedReason = reason
        s.gate.signalAll()
        engineExec.execute { clearPlan(s) }
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
                if (s.readers.values.none { it.kind == ReaderKind.HTTP } && System.currentTimeMillis() - s.lastActivityAt > idleMs) {
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
        s.lastClosedAt = 0
        kick(s, immediate = true)
        return StreamReader(core, s.info.file.size) {
            core.close()
            if (s.readers.remove(id) != null) {
                s.lastActivityAt = System.currentTimeMillis()
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
        override fun onBlocked(piece: Int) = kick(s, immediate = true)

        override fun onUnblocked(piece: Int, waitedMs: Long) {
            if (waitedMs >= 200) {
                EngineStats.of(s.info.infoHash)?.readerWait(waitedMs)
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

    /** FILE_PRIO arrived (or 1.5 s passed): the stream's file priorities are in force, readers may go. */
    private fun releaseFileBarrier(s: ActiveStream, timedOut: Boolean) {
        if (s.fileReady) return
        if (timedOut) {
            val intended = s.info.file.index
            val ok = runCatching { s.handle.filePriority(intended) != Priority.IGNORE }.getOrDefault(true)
            if (!ok) { engineExec.schedule({ releaseFileBarrier(s, true) }, 500, TimeUnit.MILLISECONDS); return }
        }
        s.fileReady = true
        s.gate.signalAll()
        kick(s, immediate = true)
    }

    private fun kick(s: ActiveStream, immediate: Boolean) {
        if (immediate) {
            runCatching { engineExec.execute { replan(s) } }
            return
        }
        if (s.pending.compareAndSet(false, true)) runCatching { engineExec.schedule({ replan(s) }, 250, TimeUnit.MILLISECONDS) }
    }

    /**
     * Interim scheduler (docs/STREAMING_V2.md §12, phase 2): startup pieces plus a read-ahead window
     * sized in seconds of video, applied incrementally. New deadlines are set before departed ones
     * are reset, and the deadline list is never cleared while streaming, so libtorrent keeps its
     * in-flight requests instead of cancelling and re-requesting them on every piece.
     */
    private fun replan(s: ActiveStream) {
        s.pending.set(false)
        if (s.closedReason != null || streams[s.info.infoHash] !== s) return
        val gate = s.gate
        val wanted = LinkedHashMap<Int, Int>()
        fun add(list: List<PieceDeadline>) = list.forEach { (p, ms) -> if (!gate.pieces.has(p)) wanted.merge(p, ms, ::minOf) }
        add(s.planner.startup())
        val window = StreamPlanner.windowBytes(s.bytesPerSec, s.info.span.pieceLength)
        val positions = s.readers.values.map { it.position }.toMutableList()
        if (s.lastClosedAt > 0 && EngineStats.now() - s.lastClosedAt < GHOST_MS) positions += s.lastClosedPos
        positions.forEach { add(s.planner.window(it, window)) }
        s.readers.values.forEach { r -> r.blockedPiece.takeIf { it >= 0 && !gate.pieces.has(it) }?.let { wanted[it] = 0 } }

        val h = s.handle
        var sets = 0
        for ((p, ms) in wanted) {
            val old = s.applied[p]
            if (old == null || (ms == 0 && old != 0) || kotlin.math.abs(old - ms) > 1_000) {
                runCatching { h.setPieceDeadline(p, ms) }
                s.applied[p] = ms
                sets++
            }
        }
        val gone = s.applied.keys.filter { it !in wanted }
        for (p in gone) {
            s.applied.remove(p)
            // Had pieces leave libtorrent's list by themselves; others go back to normal priority
            // (a reset leaves them at 1, below the rest of the file).
            if (!gate.pieces.has(p)) runCatching { h.resetPieceDeadline(p); h.piecePriority(p, Priority.DEFAULT) }
        }
        if (sets > 0 || gone.isNotEmpty()) s.lastPlanAt = EngineStats.now()
    }

    /** Stream over: drop its deadlines and put its pieces back at normal priority (no leftover 1s). */
    private fun clearPlan(s: ActiveStream) {
        val h = s.handle
        runCatching { h.clearPieceDeadlines() }
        val gate = s.gate
        s.applied.keys.filter { !gate.pieces.has(it) }.forEach { p -> runCatching { h.piecePriority(p, Priority.DEFAULT) } }
        s.applied.clear()
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
        val budget = if (explicit) 0 else CachePolicy.budget(formFactor, freeBytes, cache)
        val evict = CachePolicy.evict(entries, budget, freeBytes, CachePolicy.hardFloor(formFactor), explicit)
        evict.forEach { key ->
            synchronized(streamLock) {
                // A player may have started this torrent again since the list was made.
                if (!streams.containsKey(key) && !starting.containsKey(key)) remove(key, deleteFiles = true)
            }
        }
        if (evict.isNotEmpty()) Log.i(TAG, "cache trimmed ${evict.size} (budget ${budget shr 20} MiB, free ${freeBytes shr 20} MiB)")
    }

    /** Bytes held by stream-mode torrents (downloaded pieces, not sparse file lengths). */
    fun cacheBytes(): Long = metas.values.filter { it.mode == TorrentMode.STREAM }.sumOf { streamBytes(it.infoHash) }

    private fun streamBytes(hash: String): Long =
        snapshot(hash)?.doneBytes?.takeIf { it > 0 } ?: (find(hash)?.status()?.totalDone() ?: 0L)

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
                val decided = RestorePolicy.restore(
                    RestoreFlags(
                        autoManaged = saved.and_(TorrentFlags.AUTO_MANAGED).non_zero(),
                        paused = saved.and_(TorrentFlags.PAUSED).non_zero(),
                        sequential = saved.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero(),
                    ),
                    RestoreMeta(meta.mode == TorrentMode.DOWNLOAD, meta.userPaused, meta.seedingDone, meta.pausedForSpace, meta.pausedForStream, meta.wasAutoManaged),
                )
                var flags = saved.without(TorrentFlags.AUTO_MANAGED).without(TorrentFlags.PAUSED).without(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                if (decided.autoManaged) flags = flags.or_(TorrentFlags.AUTO_MANAGED)
                if (decided.paused) flags = flags.or_(TorrentFlags.PAUSED)
                if (raw == null && meta.mode == TorrentMode.STREAM) flags = flags.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
                params.setFlags(flags)
                // libtorrent saves these in resume data: never let an old throttle or cap come back.
                params.setDownloadLimit(0)
                params.setUploadLimit(0)
                params.setMaxConnections(-1)
                params.piecePriorities(arrayOf<Priority>())
                meta = meta.copy(pausedForStream = false, wasAutoManaged = null, savePath = meta.savePath ?: params.getSavePath())
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

    private class ActiveStream(val info: StreamInfo, val handle: TorrentHandle, val planner: StreamPlanner, val gate: PieceGate) {
        /** Open readers (in-app player, external players, bench). */
        val readers = ConcurrentHashMap<Long, StreamReaderCore>()
        @Volatile var lastActivityAt: Long = System.currentTimeMillis()
        /** Set when the stream stops or is superseded: every reader fails with it. */
        @Volatile var closedReason: java.io.IOException? = null
        /** False while new file priorities are being applied (pieces may still be in the part file). */
        @Volatile var fileReady = true
        /** Media bytes per second (from the player's duration) for read-ahead sizing. */
        @Volatile var bytesPerSec: Long? = null
        @Volatile var lastClosedPos = 0L
        @Volatile var lastClosedAt = 0L
        @Volatile var lastPlanAt = 0L
        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
        val flushPolls: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        /** Deadlines we've set (piece → ms); engine thread only. */
        val applied = HashMap<Int, Int>()
    }

    companion object {
        private const val TAG = "ReelEngine"
        private const val METADATA_TIMEOUT_MS = 90_000L
        /** A closed reader's window is kept this long (ExoPlayer reopens after extractor seeks). */
        private const val GHOST_MS = 1_500L
        /** Only a marker now: the real budget comes from [CachePolicy] for this device. */
        const val DEFAULT_CACHE_BUDGET = -1L

        private fun is64Bit() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && android.os.Process.is64Bit()

        /** The canonical key ([TorrentKey]) of a libtorrent info-hash pair. */
        fun keyOf(ih: InfoHash): String = TorrentKey.canonical(if (ih.hasV1()) ih.v1.toHex() else null, ih.best.toHex())
    }
}

/** The torrent's canonical key: v1 hex when it has one, so hybrid torrents don't change key at metadata. */
private fun TorrentHandle.key(): String = TorrentEngine.keyOf(InfoHash(swig().info_hashes()))

private fun torrent_flags_t.without(flag: torrent_flags_t): torrent_flags_t =
    if (and_(flag).non_zero()) xor_(flag) else this
