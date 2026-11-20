package com.kirtanya.reel.download

import android.net.Uri
import android.os.StatFs
import android.util.AtomicFile
import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.FatNames
import com.kirtanya.reel.core.homedisk.HomeStore
import com.kirtanya.reel.core.homedisk.UsbStore
import com.kirtanya.reel.core.httpdl.FailKind
import com.kirtanya.reel.core.httpdl.HttpDownload
import com.kirtanya.reel.core.httpdl.HttpDownloads
import com.kirtanya.reel.core.httpdl.HttpFetcher
import com.kirtanya.reel.core.httpdl.HttpPlaceholderGuard
import com.kirtanya.reel.core.httpdl.HttpPlacement
import com.kirtanya.reel.core.httpdl.HttpState
import com.kirtanya.reel.core.httpdl.RetryPolicy
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.sources.CHROME_UA
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.engine.EngineService
import com.kirtanya.reel.engine.EngineStats
import com.kirtanya.reel.engine.TorrentMode
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.engine.TorrentSnapshot
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * Direct (debrid / HTTP) downloads (docs/HOME_DISK.md §25): replaces Android's `DownloadManager`. Owns the persisted list
 * (`files/http_downloads.json`), one worker per active row, and the rows' view for the Downloads screen as [TorrentSnapshot]s with
 * `http = true` and the key `http:<id>`. The fetching, resuming, retry rules and the placeholder guard are pure and live in `:core/httpdl`.
 * The process is kept alive by [EngineService] (foreground) while any row is active.
 */
class HttpDownloader(private val app: ReelApp) {
    private val file = AtomicFile(File(app.filesDir, "http_downloads.json"))
    private val lock = Any()
    private val list = MutableStateFlow(HttpDownloads.parse(runCatching { file.readFully().decodeToString() }.getOrNull()))
    private val jobs = ConcurrentHashMap<String, Job>()
    private val fetcher by lazy { HttpFetcher(app.http, CHROME_UA) }

    /** Every row, as saved. */
    val items: StateFlow<List<HttpDownload>> = list.asStateFlow()

    private val _snapshots = MutableStateFlow(emptyList<TorrentSnapshot>())

    /** The rows as the Downloads screen, the badges and the launcher see downloads. Refreshed about once a second while something moves. */
    val snapshots: StateFlow<List<TorrentSnapshot>> = _snapshots.asStateFlow()

    @Volatile private var closing = false
    @Volatile private var ticker: kotlinx.coroutines.Job? = null

    init {
        refresh()
    }

    sealed interface Added {
        data class Started(val key: String) : Added
        data class Refused(val message: String) : Added
    }

    /** Rows doing something (or about to): the process must not be stopped for them. */
    fun activeCount(): Int = list.value.count { it.state == HttpState.QUEUED || it.state == HttpState.DOWNLOADING || it.state == HttpState.RETRYING }

    fun get(key: String): HttpDownload? {
        val id = HttpDownloads.idOf(key)
        return list.value.firstOrNull { it.id == id }
    }

    // ------------------------------------------------------------------ adding

    /**
     * Adds one direct source. Blocking (reads the disk's free space): call off the main thread. [src] says who asked
     * (button | mylist | auto | prefetch | link) for the `dl-place` line. A link already downloading is not added twice.
     */
    fun add(source: Source, title: String, origin: DownloadOrigin?, src: String): Added {
        val url = source.url?.takeIf { source.kind == SourceKind.DIRECT } ?: return Added.Refused("This source has no link to download")
        val existing = list.value.firstOrNull { it.url == url }
        if (existing != null) {
            if (existing.state != HttpState.FAILED && existing.state != HttpState.NOT_READY) return Added.Started(existing.key)
            remove(existing.key)
        }
        val decision = app.downloadPlacer.decideHttp(src, source.sizeBytes)
        decision.refused?.let { return Added.Refused(it.message) }
        val place = decision.place ?: return Added.Refused(HttpPlacement.noSpace(source.sizeBytes))
        val d = HttpDownload(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            url = url,
            headers = source.headers,
            name = displayName(source, title),
            fileName = fileNameOf(source, url, title),
            place = place.tag,
            baseDir = decision.baseDir?.path.orEmpty(),
            volumeId = decision.volumeId,
            expectedBytes = source.sizeBytes,
            provider = source.provider,
            isTitle = origin != null,
            createdAt = System.currentTimeMillis(),
        )
        origin?.let { app.downloadOrigins.put(d.key, it) }
        synchronized(lock) {
            list.value = list.value + d
            persist()
        }
        log("start", d, "src" to src, "size" to source.sizeBytes?.shr(20))
        refresh()
        pump()
        return Added.Started(d.key)
    }

    // ------------------------------------------------------------------ the user's controls

    fun pause(key: String) {
        val id = HttpDownloads.idOf(key)
        val d = get(key) ?: return
        if (d.state == HttpState.DONE || d.state == HttpState.NOT_READY) return
        update(id) { it.copy(state = HttpState.PAUSED, error = null) }
        jobs[id]?.stop()
        log("paused", d, "why" to "user")
        refresh()
    }

    /** Resume, and Retry after a failure or "not ready". */
    fun resume(key: String) {
        val id = HttpDownloads.idOf(key)
        val d = get(key) ?: return
        if (d.state == HttpState.DONE || jobs.containsKey(id) && d.state != HttpState.PAUSED) return
        update(id) { it.copy(state = HttpState.QUEUED, error = null, doneBytes = if (it.state == HttpState.NOT_READY) 0 else it.doneBytes) }
        log("resume", d, "from" to d.doneBytes.shr(20))
        refresh()
        pump()
    }

    /** Stops the row and deletes its folder. The origin is forgotten too. */
    fun remove(key: String) {
        val id = HttpDownloads.idOf(key)
        val d = get(key) ?: return
        val job = jobs[id]
        job?.stop()
        synchronized(lock) {
            list.value = list.value.filter { it.id != id }
            persist()
        }
        app.downloadOrigins.remove(key)
        refresh()
        app.appScope.launch(Dispatchers.IO) {
            job?.join()
            deleteFiles(d)
        }
    }

    // ------------------------------------------------------------------ the files

    /** The folder of [d]'s disk or the TV's; null while its USB disk is away. */
    fun folderOf(d: HttpDownload): File? =
        if (d.place == DownloadPlace.USB.tag) app.downloadPlacer.httpUsbDir(d.volumeId) else internalDir(app).also { it.mkdirs() }

    /** The finished (or part) file's store, for playing. Blocking: reads the volumes. Null while the disk is away. */
    fun storeOf(d: HttpDownload): HomeStore? = folderOf(d)?.let { storeIn(it, d.place) }

    private fun storeIn(dir: File, place: String) = if (place == DownloadPlace.USB.tag) UsbStore(dir) else UsbStore(dir, NO_SPLIT)

    private fun deleteFiles(d: HttpDownload) {
        val dir = folderOf(d) ?: return
        runCatching { storeIn(dir, d.place).delete(d.id) }
        runCatching { File(dir, d.id).deleteRecursively() }
    }

    // ------------------------------------------------------------------ the workers

    /** Starts what is queued (three at a time) and what waits for a disk that came back. Safe to call any time. */
    fun start() {
        if (closing) return
        if (list.value.any { it.state == HttpState.QUEUED || it.state == HttpState.WAITING_DISK }) pump()
    }

    private fun pump() {
        if (closing) return
        val begin = ArrayList<HttpDownload>()
        synchronized(lock) {
            var free = MAX_PARALLEL - jobs.size
            for (d in list.value.filter { it.state == HttpState.QUEUED && !jobs.containsKey(it.id) }.sortedBy { it.createdAt }) {
                if (free <= 0) break
                val job = Job(d.id)
                if (jobs.putIfAbsent(d.id, job) == null) {
                    begin += d
                    free--
                }
            }
        }
        if (begin.isEmpty()) {
            ensureTicker()
            return
        }
        runCatching { EngineService.start(app) }
        for (d in begin) {
            val job = jobs[d.id] ?: continue
            job.coroutine = app.appScope.launch(Dispatchers.IO) { runJob(job) }
        }
        ensureTicker()
    }

    private inner class Job(val id: String) : HttpFetcher.Listener {
        @Volatile override var cancelled = false
        @Volatile var call: Call? = null
        @Volatile var done = 0L
        @Volatile var coroutine: kotlinx.coroutines.Job? = null
        var lastDone = 0L
        var lastTime = 0L
        var bps = 0L
        var lastLog = 0L
        var current: HttpDownload? = null

        override fun onCall(call: Call) {
            this.call = call
            if (cancelled) call.cancel()
        }

        override fun onStart(total: Long, validator: String?, restarted: Boolean) {
            val before = current ?: return
            if (restarted) done = 0
            update(id) { it.copy(totalBytes = total, validator = validator, doneBytes = if (restarted) 0 else it.doneBytes, state = if (it.state == HttpState.PAUSED) it.state else HttpState.DOWNLOADING, error = null) }
            log(if (before.doneBytes > 0 && !restarted) "resume" else "start", before, "size" to total.shr(20), "split" to if (before.place == DownloadPlace.USB.tag && total >= com.kirtanya.reel.core.homedisk.DownloadPlacement.FAT_LIMIT) 1 else 0, "from" to if (restarted) 0 else before.doneBytes.shr(20))
        }

        override fun onBytes(done: Long) {
            this.done = done
        }

        override fun onFlushed(done: Long) {
            this.done = maxOf(this.done, done)
            update(id) { it.copy(doneBytes = done) }
        }

        fun stop() {
            cancelled = true
            call?.cancel()
        }

        suspend fun join() {
            coroutine?.join()
        }
    }

    private suspend fun runJob(job: Job) {
        var attempt = 1
        var store: UsbStore? = null
        try {
            while (!job.cancelled && !closing) {
                val d = get(HttpDownloads.keyOf(job.id)) ?: return
                if (d.state != HttpState.QUEUED && d.state != HttpState.DOWNLOADING && d.state != HttpState.RETRYING) return
                job.current = d
                job.done = d.doneBytes
                val dir = folderOf(d)
                if (dir == null) {
                    update(d.id) { it.copy(state = HttpState.WAITING_DISK, error = "USB disk not plugged in") }
                    log("paused", d, "why" to "disk")
                    return
                }
                val place = if (d.place == DownloadPlace.USB.tag) DownloadPlace.USB else DownloadPlace.TV
                val st = storeIn(dir, d.place).also { store = it as? UsbStore }
                update(d.id) { if (it.state == HttpState.PAUSED) it else it.copy(state = HttpState.DOWNLOADING) }
                val outcome = fetcher.fetch(d, st, place, { runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L) }, job)
                (st as? UsbStore)?.closeAll()
                when (outcome) {
                    is HttpFetcher.Outcome.Done -> {
                        update(d.id) { it.copy(state = HttpState.DONE, totalBytes = outcome.total, doneBytes = outcome.total, error = null, finishedAt = System.currentTimeMillis()) }
                        log("done", d, "size" to outcome.total.shr(20))
                        return
                    }
                    is HttpFetcher.Outcome.Retry -> {
                        attempt = RetryPolicy.nextAttempt(attempt, outcome.progressed)
                        if (RetryPolicy.giveUp(outcome.kind, attempt)) {
                            val line = if (outcome.kind == FailKind.NETWORK) "Could not reach the server. Resume to try again" else "The server is busy. Resume to try again"
                            update(d.id) { it.copy(state = HttpState.FAILED, error = line) }
                            log("fail", d, "why" to outcome.kind.name.lowercase(), "tries" to attempt)
                            return
                        }
                        update(d.id) { if (it.state == HttpState.PAUSED) it else it.copy(state = HttpState.RETRYING, error = outcome.message) }
                        log("retry", d, "why" to outcome.kind.name.lowercase(), "try" to attempt)
                        var wait = RetryPolicy.delayMs(attempt, outcome.retryAfterSec)
                        while (wait > 0 && !job.cancelled && !closing) {
                            delay(minOf(wait, 500L))
                            wait -= 500L
                        }
                    }
                    is HttpFetcher.Outcome.Failed -> {
                        update(d.id) { it.copy(state = HttpState.FAILED, error = outcome.message) }
                        log("fail", d, "why" to "answer")
                        return
                    }
                    is HttpFetcher.Outcome.NotReady -> {
                        deleteFiles(d)
                        update(d.id) { it.copy(state = HttpState.NOT_READY, error = HttpDownloads.notReadyLine(it.provider), doneBytes = 0, totalBytes = 0, validator = null) }
                        log("placeholder", d, "why" to outcome.stat)
                        return
                    }
                    HttpFetcher.Outcome.NotVideo -> {
                        deleteFiles(d)
                        update(d.id) { it.copy(state = HttpState.FAILED, error = HttpPlaceholderGuard.NOT_VIDEO_MESSAGE, doneBytes = 0, totalBytes = 0, validator = null) }
                        log("fail", d, "why" to "not-video")
                        return
                    }
                    is HttpFetcher.Outcome.NoSpace -> {
                        deleteFiles(d)
                        update(d.id) { it.copy(state = HttpState.FAILED, error = HttpPlacement.noSpace(outcome.total), doneBytes = 0) }
                        log("fail", d, "why" to "no-space", "size" to outcome.total.shr(20))
                        return
                    }
                    is HttpFetcher.Outcome.DiskError -> {
                        // The disk was unplugged under us: wait for it; anything else is a real error.
                        if (folderOf(d) == null) {
                            update(d.id) { it.copy(state = HttpState.WAITING_DISK, error = "USB disk not plugged in") }
                            log("paused", d, "why" to "disk-gone")
                        } else {
                            update(d.id) { it.copy(state = HttpState.FAILED, error = "Could not write to the disk. ${outcome.message}") }
                            log("fail", d, "why" to "disk")
                        }
                        return
                    }
                    HttpFetcher.Outcome.Cancelled -> return
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "download worker failed", e)
            update(job.id) { if (it.state == HttpState.DONE) it else it.copy(state = HttpState.FAILED, error = "Something went wrong. Resume to try again") }
        } finally {
            runCatching { store?.closeAll() }
            jobs.remove(job.id)
            refresh()
            pump()
        }
    }

    /** Saves the positions and stops every worker (Quit): they stay queued and resume at the next start. Blocking, up to ~2 s. */
    fun shutdown() {
        closing = true
        jobs.values.forEach { it.stop() }
        var waited = 0
        while (jobs.isNotEmpty() && waited < 2_000) {
            Thread.sleep(50)
            waited += 50
        }
    }

    // ------------------------------------------------------------------ the list

    private fun update(id: String, change: (HttpDownload) -> HttpDownload) = synchronized(lock) {
        val current = list.value
        val i = current.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        val next = change(current[i])
        if (next == current[i]) return@synchronized
        list.value = current.toMutableList().also { it[i] = next }
        persist()
    }

    private fun persist() {
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = file.startWrite()
            out!!.write(HttpDownloads.encode(list.value).toByteArray())
            file.finishWrite(out)
        }.onFailure {
            out?.let(file::failWrite)
            Log.w(TAG, "save failed", it)
        }
    }

    // ------------------------------------------------------------------ what the screens see

    private fun refresh() {
        val now = System.currentTimeMillis()
        _snapshots.value = list.value.map { snapshotOf(it, jobs[it.id], now) }
    }

    private fun snapshotOf(d: HttpDownload, job: Job?, now: Long): TorrentSnapshot {
        val running = d.state == HttpState.DOWNLOADING || d.state == HttpState.RETRYING || d.state == HttpState.QUEUED
        val done = when {
            d.state == HttpState.DONE -> d.totalBytes
            job != null && running -> job.done
            else -> d.doneBytes
        }
        val wanted = if (d.totalBytes > 0) d.totalBytes else d.expectedBytes ?: 0L
        val phase = when (d.state) {
            HttpState.QUEUED, HttpState.DOWNLOADING, HttpState.RETRYING -> TorrentPhase.DOWNLOADING
            HttpState.PAUSED, HttpState.WAITING_DISK -> TorrentPhase.PAUSED
            HttpState.DONE -> TorrentPhase.FINISHED
            HttpState.FAILED, HttpState.NOT_READY -> TorrentPhase.ERROR
        }
        return TorrentSnapshot(
            infoHash = d.key,
            name = d.name,
            mode = TorrentMode.DOWNLOAD,
            phase = phase,
            progress = if (wanted > 0) (done.toFloat() / wanted).coerceIn(0f, 1f) else 0f,
            wantedBytes = wanted,
            doneBytes = done,
            downBps = if (d.state == HttpState.DOWNLOADING) (job?.bps ?: 0L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() else 0,
            upBps = 0,
            peers = 0,
            seeds = 0,
            hasMetadata = true,
            savePath = File(d.baseDir, d.id).path,
            error = if (d.state == HttpState.FAILED || d.state == HttpState.NOT_READY) d.error else null,
            userPaused = d.state == HttpState.PAUSED,
            notice = when (d.state) {
                HttpState.RETRYING -> d.error
                HttpState.WAITING_DISK -> d.error
                HttpState.QUEUED -> if (job == null) "Waiting for a free slot" else null
                else -> null
            },
            onUsb = d.place == DownloadPlace.USB.tag,
            waitingForDisk = d.state == HttpState.WAITING_DISK,
            http = true,
            notReady = d.state == HttpState.NOT_READY,
        )
    }

    /** One tick a second while a row moves (speeds, the progress bar) and every few seconds while a row waits for its disk. Ends itself when nothing is left to watch. */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        if (jobs.isEmpty() && list.value.none { it.state == HttpState.WAITING_DISK }) return
        ticker = app.appScope.launch(Dispatchers.IO) {
            var n = 0
            while (!closing) {
                delay(1_000)
                n++
                val now = System.currentTimeMillis()
                for (job in jobs.values) {
                    if (job.lastTime > 0) {
                        val dt = (now - job.lastTime).coerceAtLeast(1)
                        val inst = ((job.done - job.lastDone).coerceAtLeast(0) * 1000) / dt
                        job.bps = if (job.bps == 0L) inst else (job.bps + inst) / 2
                    }
                    job.lastDone = job.done
                    job.lastTime = now
                    val d = job.current
                    if (d != null && now - job.lastLog >= PROGRESS_LOG_MS) {
                        if (job.lastLog > 0) log("progress", d, "done" to job.done.shr(20), "total" to (get(d.key)?.totalBytes ?: 0).shr(20), "bps" to job.bps)
                        job.lastLog = now
                    }
                }
                if (n % 5 == 0 && list.value.any { it.state == HttpState.WAITING_DISK }) {
                    list.value.filter { it.state == HttpState.WAITING_DISK }.forEach { d ->
                        if (folderOf(d) != null) {
                            update(d.id) { it.copy(state = HttpState.QUEUED, error = null) }
                            log("resume", d, "why" to "disk-back")
                        }
                    }
                    pump()
                }
                refresh()
                if (jobs.isEmpty() && list.value.none { it.state == HttpState.WAITING_DISK }) break
            }
            refresh()
        }
    }

    private fun log(state: String, d: HttpDownload, vararg more: Pair<String, Any?>) {
        val f = linkedMapOf<String, Any?>("state" to state, "id" to d.id.take(6), "place" to d.place)
        more.forEach { f[it.first] = it.second }
        EngineStats.log("dl-http", f)
    }

    companion object {
        private const val TAG = "ReelHttpDl"

        /** The folder on the USB disk, inside its `downloads` folder (docs/HOME_DISK.md §25.2). */
        const val FOLDER = "http"
        const val MAX_PARALLEL = 3
        private const val PROGRESS_LOG_MS = 30_000L

        /** The pieces' size on the TV's own storage: never split. */
        private const val NO_SPLIT = Long.MAX_VALUE / 4

        /** Where downloads go when they are not on the USB disk. */
        fun internalDir(app: ReelApp): File = File(app.filesDir, "http-downloads")

        /** The row's title: the title the user saw, else the file's name. */
        private fun displayName(source: Source, title: String): String =
            title.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: source.filename ?: source.title.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Download"

        /** A name that is safe on FAT: the source's file name, else the link's last part, else the title. */
        internal fun fileNameOf(source: Source, url: String, title: String): String {
            val fromUrl = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
            val raw = (source.filename ?: fromUrl ?: "").substringAfterLast('/').trim()
            val named = raw.ifBlank { title.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() + ".mp4" }
            return FatNames.safe(named.ifBlank { "video.mp4" })
        }
    }
}
