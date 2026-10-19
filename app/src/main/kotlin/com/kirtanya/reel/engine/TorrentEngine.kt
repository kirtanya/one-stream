package com.kirtanya.reel.engine

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.kirtanya.reel.core.magnet.DEFAULT_TRACKERS
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.PieceDeadline
import com.kirtanya.reel.core.stream.StreamPlanner
import com.kirtanya.reel.core.stream.TorrentFileEntry
import com.kirtanya.reel.core.stream.mimeTypeFor
import com.kirtanya.reel.core.stream.pickStreamFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
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
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t

/**
 * One libtorrent session for the whole app. Every torrent is either streaming (play now,
 * cached in app storage) or downloading (kept). See docs/PLAN.md §1.6.
 */
class TorrentEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = SessionManager(false)
    private val handles get() = SessionHandle(session.swig())
    private val json = Json { ignoreUnknownKeys = true }

    /** Streams are cached per torrent under here and trimmed to the cache budget. */
    val streamDir = File(context.filesDir, "streams")
    private val stateDir = File(context.filesDir, "torrents")

    private val metas = ConcurrentHashMap<String, TorrentMeta>()
    private val errors = ConcurrentHashMap<String, String>()
    private val stalls = ConcurrentHashMap<String, Int>()
    private val metadataWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pieceWaiters = ConcurrentHashMap<String, ConcurrentHashMap<Int, CompletableDeferred<Unit>>>()
    private val streams = ConcurrentHashMap<String, ActiveStream>()
    private val readerIds = AtomicLong()

    private val _torrents = MutableStateFlow<List<TorrentSnapshot>>(emptyList())
    val torrents: StateFlow<List<TorrentSnapshot>> = _torrents.asStateFlow()

    @Volatile private var started = false

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        streamDir.mkdirs()
        stateDir.mkdirs()
        session.addListener(listener)
        session.start(sessionParams())
        restore()
        scope.launch { pollLoop() }
        scope.launch { resumeSaveLoop() }
        Log.i(TAG, "engine started (32-bit=${!is64Bit()})")
    }

    /** Saves resume data for every torrent, then stops the session. */
    fun shutdown() {
        if (!started) return
        runCatching {
            handles.torrents().filter { it.isValid }.forEach { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
            Thread.sleep(1500)
        }
        session.stop()
        started = false
    }

    private fun sessionParams(): SessionParams {
        val lowEnd = !is64Bit()
        val sp = SettingsPack()
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        sp.alertQueueSize(10_000)
        sp.connectionsLimit(if (lowEnd) 120 else 200)
        sp.activeDownloads(3)
        sp.activeSeeds(3)
        sp.activeLimit(10)
        val mask = Alert.ERROR_NOTIFICATION
            .or_(Alert.STATUS_NOTIFICATION)
            .or_(Alert.STORAGE_NOTIFICATION)
            .or_(Alert.PIECE_PROGRESS_NOTIFICATION)
        sp.setInteger(settings_pack.int_types.alert_mask.swigValue(), mask.to_int())
        val params = SessionParams(sp)
        // libtorrent 2's memory-mapped disk I/O can exhaust a 32-bit address space (the TV).
        if (lowEnd) params.setPosixDiskIO()
        return params
    }

    // ---------------------------------------------------------------- adding

    /**
     * Adds a magnet link (or returns the existing torrent). Returns the info-hash.
     * For downloads, [onlyFileIndex] / [onlyFileHint] limit it to one file (e.g. one episode of a season pack).
     */
    suspend fun addMagnet(uri: String, mode: TorrentMode, onlyFileIndex: Int? = null, onlyFileHint: String? = null): String = withContext(Dispatchers.IO) {
        start()
        val magnet = Magnet.parse(uri) ?: throw EngineException("That isn't a valid magnet link")
        val hash = magnet.infoHash
        find(hash)?.let { existing ->
            if (mode == TorrentMode.DOWNLOAD && metas[hash]?.mode == TorrentMode.STREAM) keep(hash)
            if (onlyFileIndex != null || onlyFileHint != null) {
                metas[hash]?.let { saveMeta(it.copy(onlyFileIndex = onlyFileIndex, onlyFileHint = onlyFileHint)) }
                applyFileChoice(existing)
            }
            return@withContext hash
        }
        val params = try {
            AddTorrentParams.parseMagnetUri(uri.trim())
        } catch (t: Throwable) {
            throw EngineException("Couldn't read the magnet link")
        }
        params.setTrackers((params.getTrackers() + DEFAULT_TRACKERS).distinct())
        add(hash, params, mode, onlyFileIndex, onlyFileHint)
        hash
    }

    /** Adds a `.torrent` file's bytes. Returns the info-hash. */
    suspend fun addTorrentFile(bytes: ByteArray, mode: TorrentMode): String = withContext(Dispatchers.IO) {
        start()
        val info = try {
            TorrentInfo.bdecode(bytes)
        } catch (t: Throwable) {
            throw EngineException("That .torrent file is damaged")
        }
        val hash = info.infoHash().toHex()
        find(hash)?.let { return@withContext hash }
        val params = AddTorrentParams()
        params.setTorrentInfo(info)
        add(hash, params, mode)
        hash
    }

    private fun add(hash: String, params: AddTorrentParams, mode: TorrentMode, onlyFileIndex: Int? = null, onlyFileHint: String? = null) {
        params.setSavePath(saveDirFor(hash, mode).absolutePath)
        params.setFlags(flagsFor(mode, params.getFlags()))
        saveMeta(TorrentMeta(hash, mode, onlyFileIndex = onlyFileIndex, onlyFileHint = onlyFileHint))
        val ec = ErrorCode(error_code())
        val handle = handles.addTorrent(params, ec)
        if (ec.isError || !handle.isValid) {
            metas.remove(hash)
            throw EngineException(ec.message.ifBlank { "Couldn't add the torrent" })
        }
        applyFileChoice(handle)
        handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    /** For downloads limited to one file: once metadata is known, ignore every other file. */
    private fun applyFileChoice(handle: TorrentHandle) {
        val meta = metas[handle.hex()] ?: return
        if (meta.mode != TorrentMode.DOWNLOAD || (meta.onlyFileIndex == null && meta.onlyFileHint == null)) return
        val info = handle.torrentFile() ?: return // no metadata yet; METADATA_RECEIVED calls again
        val file = pickStreamFile(files(handle.hex()), meta.onlyFileIndex, meta.onlyFileHint) ?: return
        val priorities = Priority.array(Priority.IGNORE, info.numFiles())
        priorities[file.index] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
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
    }

    fun resume(hash: String) {
        val h = find(hash) ?: return
        if (metas[hash]?.mode == TorrentMode.DOWNLOAD) h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
    }

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = find(hash)
        streams.remove(hash)
        pieceWaiters.remove(hash)?.values?.forEach { it.cancel() }
        val mode = metas.remove(hash)?.mode
        if (h != null) {
            if (deleteFiles) handles.removeTorrent(h, SessionHandle.DELETE_FILES) else handles.removeTorrent(h)
        }
        File(stateDir, "$hash.resume").delete()
        File(stateDir, "$hash.json").delete()
        if (mode == TorrentMode.STREAM) File(streamDir, hash).deleteRecursively()
        refreshSnapshots()
    }

    /** "Keep": a stream becomes a normal download in the Downloads folder; fetched pieces are reused. */
    fun keep(hash: String) {
        val h = find(hash) ?: return
        val meta = metas[hash] ?: return
        if (meta.mode == TorrentMode.DOWNLOAD) return
        h.moveStorage(downloadsDir().absolutePath)
        h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
        saveMeta(meta.copy(mode = TorrentMode.DOWNLOAD))
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
     * Waits for metadata, picks the file, focuses the torrent on it and queues the
     * startup pieces. The local server can serve [StreamInfo] as soon as this returns.
     */
    suspend fun prepareStream(hash: String, fileIdx: Int? = null, filenameHint: String? = null): StreamInfo =
        withContext(Dispatchers.IO) {
            val handle = find(hash) ?: throw EngineException("Torrent not found")
            awaitMetadata(hash, METADATA_TIMEOUT_MS)
            val info = handle.torrentFile() ?: throw EngineException("No metadata yet")
            val fs = info.files()
            val file = pickStreamFile(files(hash), fileIdx, filenameHint) ?: throw EngineException("This torrent has no files")

            val mode = metas[hash]?.mode ?: TorrentMode.STREAM
            if (mode == TorrentMode.STREAM) {
                val priorities = Priority.array(Priority.IGNORE, fs.numFiles())
                priorities[file.index] = Priority.DEFAULT
                handle.prioritizeFiles(priorities)
            } else if (handle.filePriority(file.index) == Priority.IGNORE) {
                handle.filePriority(file.index, Priority.DEFAULT)
            }
            handle.resume()

            val span = FileSpan(fs.fileOffset(file.index), fs.fileSize(file.index), info.pieceLength(), info.numPieces())
            val stream = StreamInfo(
                infoHash = hash,
                file = file,
                span = span,
                mimeType = mimeTypeFor(file.name),
                path = File(handle.savePath(), fs.filePath(file.index)),
            )
            metas[hash]?.let { saveMeta(it.copy(streamFileIndex = file.index, lastUsedAt = System.currentTimeMillis())) }
            streams[hash] = ActiveStream(stream, StreamPlanner(span))
            retarget(hash)
            stream
        }

    fun streamInfo(hash: String): StreamInfo? = streams[hash]?.info

    fun isStreaming(hash: String): Boolean = streams.containsKey(hash)

    /** Called when the player is done. Stream-mode torrents pause and stay in the cache. */
    fun stopStream(hash: String) {
        val stream = streams.remove(hash) ?: return
        val h = find(hash) ?: return
        h.clearPieceDeadlines()
        if (metas[hash]?.mode == TorrentMode.STREAM) {
            h.pause()
            h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        }
        Log.i(TAG, "stream stopped ${stream.info.file.name}")
        scope.launch(Dispatchers.IO) { trimCache(DEFAULT_CACHE_BUDGET) }
    }

    fun openReader(hash: String, filePos: Long): Long {
        val id = readerIds.incrementAndGet()
        streams[hash]?.readers?.put(id, filePos)
        retarget(hash)
        return id
    }

    /** The reader moved to [filePos]; re-plans deadlines when it enters a new piece. */
    fun readerAt(hash: String, readerId: Long, filePos: Long) {
        val stream = streams[hash] ?: return
        val old = stream.readers.put(readerId, filePos)
        if (old == null || stream.info.span.pieceOf(old) != stream.info.span.pieceOf(filePos)) retarget(hash)
    }

    fun closeReader(hash: String, readerId: Long) {
        streams[hash]?.readers?.remove(readerId) ?: return
        retarget(hash)
    }

    /** Re-sets piece deadlines: startup pieces (until fetched) plus a window ahead of every reader. */
    private fun retarget(hash: String) {
        val stream = streams[hash] ?: return
        val h = find(hash) ?: return
        synchronized(stream) {
            val wanted = LinkedHashMap<Int, Int>()
            fun addAll(list: List<PieceDeadline>) = list.forEach { (piece, ms) ->
                if (!h.havePiece(piece)) wanted.merge(piece, ms, ::minOf)
            }
            addAll(stream.planner.startup())
            val windowBytes = StreamPlanner.windowBytes(stream.info.file.size, durationSec = null)
            stream.readers.values.forEach { addAll(stream.planner.window(it, windowBytes)) }
            h.clearPieceDeadlines()
            wanted.forEach { (piece, ms) -> h.setPieceDeadline(piece, ms) }
        }
    }

    /** Suspends until [piece] is downloaded and verified. Returns false on timeout. */
    suspend fun awaitPiece(hash: String, piece: Int, timeoutMs: Long): Boolean {
        val h = find(hash) ?: return false
        if (h.havePiece(piece)) return true
        val waiter = pieceWaiters.getOrPut(hash) { ConcurrentHashMap() }.getOrPut(piece) { CompletableDeferred() }
        if (h.havePiece(piece)) {
            waiter.complete(Unit)
            return true
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() } != null
    }

    fun awaitPieceBlocking(hash: String, piece: Int, timeoutMs: Long): Boolean =
        runBlocking { awaitPiece(hash, piece, timeoutMs) }

    private suspend fun awaitMetadata(hash: String, timeoutMs: Long) {
        val h = find(hash) ?: throw EngineException("Torrent not found")
        if (h.status().hasMetadata()) return
        val waiter = metadataWaiters.getOrPut(hash) { CompletableDeferred() }
        if (h.status().hasMetadata()) return
        try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (t: Throwable) {
            throw EngineException("No one is sharing this right now. Try another source.")
        } finally {
            metadataWaiters.remove(hash)
        }
    }

    /** Deletes the oldest inactive streams until the stream cache fits [budgetBytes]. */
    fun trimCache(budgetBytes: Long) {
        val cached = metas.values
            .filter { it.mode == TorrentMode.STREAM && !streams.containsKey(it.infoHash) }
            .sortedBy { it.lastUsedAt }
        var used = cacheBytes()
        for (meta in cached) {
            if (used <= budgetBytes) break
            val size = streamBytes(meta.infoHash)
            remove(meta.infoHash, deleteFiles = true)
            used -= size
        }
    }

    /** Bytes held by stream-mode torrents (downloaded pieces, not sparse file lengths). */
    fun cacheBytes(): Long = metas.values.filter { it.mode == TorrentMode.STREAM }.sumOf { streamBytes(it.infoHash) }

    private fun streamBytes(hash: String): Long = find(hash)?.status()?.totalDone() ?: 0L

    // ---------------------------------------------------------------- alerts + polling

    private val listener = object : AlertListener {
        override fun types(): IntArray? = null

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
                pieceWaiters[a.handle().hex()]?.remove(a.pieceIndex())?.complete(Unit)
            }
            AlertType.METADATA_RECEIVED -> {
                val h = (alert as TorrentAlert<*>).handle()
                metadataWaiters[h.hex()]?.complete(Unit)
                applyFileChoice(h)
                h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
            AlertType.SAVE_RESUME_DATA -> {
                val a = alert as SaveResumeDataAlert
                val hash = a.handle().hex()
                if (metas.containsKey(hash)) {
                    File(stateDir, "$hash.resume").writeBytes(AddTorrentParams.writeResumeDataBuf(a.params()))
                }
            }
            AlertType.TORRENT_FINISHED -> (alert as TorrentAlert<*>).handle().saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            AlertType.TORRENT_ERROR -> {
                val a = alert as TorrentErrorAlert
                errors[a.handle().hex()] = a.error().message
            }
            else -> Unit
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            runCatching { refreshSnapshots() }.onFailure { Log.w(TAG, "poll failed", it) }
            delay(1000)
        }
    }

    private suspend fun resumeSaveLoop() {
        while (scope.isActive) {
            delay(60_000)
            runCatching {
                handles.torrents().filter { it.isValid && it.needSaveResumeData() }
                    .forEach { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
            }
        }
    }

    private fun refreshSnapshots() {
        _torrents.value = handles.torrents().filter { it.isValid }.mapNotNull { h ->
            val hash = h.hex()
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
        File(stateDir, "${meta.infoHash}.json").writeText(json.encodeToString(TorrentMeta.serializer(), meta))
    }

    private fun restore() {
        stateDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
            runCatching {
                val meta = json.decodeFromString(TorrentMeta.serializer(), file.readText())
                val resume = File(stateDir, "${meta.infoHash}.resume")
                if (!resume.isFile) {
                    file.delete()
                    return@runCatching
                }
                val ec = error_code()
                val raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(resume.readBytes()), ec)
                if (ec.value() != 0) error("bad resume data: ${ec.message()}")
                val params = AddTorrentParams(raw)
                var flags = flagsFor(meta.mode, params.getFlags())
                // Streams come back paused until someone plays them again.
                if (meta.mode == TorrentMode.STREAM) flags = flags.or_(TorrentFlags.PAUSED)
                params.setFlags(flags)
                metas[meta.infoHash] = meta
                val handle = handles.addTorrent(params, ErrorCode(error_code()))
                if (!handle.isValid) metas.remove(meta.infoHash)
            }.onFailure { Log.w(TAG, "restore ${file.name} failed", it) }
        }
    }

    private fun find(hash: String): TorrentHandle? =
        runCatching { handles.findTorrent(Sha1Hash.parseHex(hash)) }.getOrNull()?.takeIf { it.isValid }

    private class ActiveStream(val info: StreamInfo, val planner: StreamPlanner) {
        /** Each open HTTP connection from the player → its current file position. */
        val readers = ConcurrentHashMap<Long, Long>()
    }

    companion object {
        private const val TAG = "ReelEngine"
        private const val METADATA_TIMEOUT_MS = 90_000L
        const val DEFAULT_CACHE_BUDGET = 2L * 1024 * 1024 * 1024

        private fun is64Bit() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && android.os.Process.is64Bit()
    }
}

private fun TorrentHandle.hex(): String = infoHash().toHex()

private fun torrent_flags_t.without(flag: torrent_flags_t): torrent_flags_t =
    if (and_(flag).non_zero()) xor_(flag) else this
