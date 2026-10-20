package com.kirtanya.reel.report

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.report.LogWindow
import com.kirtanya.reel.core.report.ReportEntry
import com.kirtanya.reel.core.report.ReportPolicy
import com.kirtanya.reel.engine.EngineFlags
import com.kirtanya.reel.engine.EngineStats
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Problem reports (docs/MACHINE_FIRST_PLAN.md step 1): when something goes wrong, save what a later
 * fix needs — what was on screen, the app's last 2 minutes of logs, the stream's state — so fixes
 * start from real data instead of a description. Reports are for Claude, not for the user.
 *
 * Each report is a folder under the app's external files dir, so `adb pull` reads it on a release
 * build: `/sdcard/Android/data/com.kirtanya.reel/files/reports/<yyyyMMdd-HHmmss>-<kind>/` with
 * report.json, logs.txt, and (when there is one) screen.png and stack.txt. tool/pull_reports.sh
 * brings them to the Mac.
 *
 * Saved automatically for crashes, freezes, Android's own exit records (ANR, native crash, low
 * memory), long buffering and player errors; by hand from a shake (phone), the player's settings
 * panel, or the SourcesProbe broadcast.
 */
class ReportCenter(private val app: ReelApp) {
    /** A screen that can describe itself in a report (the player: title, source, position). */
    interface Screen {
        /** Main thread only: what this screen shows or plays right now. */
        fun reportState(): Map<String, Any?>
        /** The torrent this screen streams (its engine state goes in the report). Any thread. */
        fun reportStreamKey(): String? = null
        /** The video layer to put under the window in screen.png. */
        fun reportVideo(): SurfaceView? = null
    }

    /** What was on screen when the problem happened: taken first, saved later (after the note dialog). */
    class Capture internal constructor(
        internal val screen: Map<String, Any?>,
        internal val streamKey: String?,
        internal val shot: CompletableFuture<ScreenCapture.Shot?>,
    ) {
        fun discard() { shot.thenAccept { it?.recycle() } }
    }

    private class Report(
        val kind: String,
        val detail: String?,
        val note: String?,
        val screen: Map<String, Any?>,
        val streamKey: String?,
        val extra: Map<String, Any?>,
        val key: String?,
        val time: Long = System.currentTimeMillis(),
        val shot: ScreenCapture.Shot? = null,
        val stack: String? = null,
        val files: Map<String, ByteArray> = emptyMap(),
        /** Whose logs: this process, or (null) every run of ours still in the log buffer. */
        val logPid: Int? = Process.myPid(),
        /** The logs kept end here (a previous run's exit time for exit reports). */
        val logEnd: Long = time,
        /** The engine as it is now; off for reports about a previous run. */
        val engine: Boolean = true,
    )

    /** Saving runs here, one report at a time, off the main thread. */
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "reel-report").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { prettyPrint = true }
    /** Reports from the last [ReportPolicy.AUTO_WINDOW_MS], saved or still being written (rate limit). */
    private val recent = CopyOnWriteArrayList<ReportEntry>()
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var installed = false
    private var dialogOpen = false
    private val shake by lazy { ShakeDetector(app) { onShake() } }

    /** The activity on screen (or last on screen), for reports that don't come from one. */
    val currentActivity: Activity? get() = resumed?.get()

    val dir: File? get() = app.getExternalFilesDir(DIR)

    /** Called first thing in [ReelApp.onCreate]. */
    fun install() {
        // Screen renders on the Mac (Robolectric) start the app too: no handlers, threads or logcat there.
        if (Build.FINGERPRINT == "robolectric" || installed) return
        installed = true
        installCrashHandler()
        app.registerActivityLifecycleCallbacks(lifecycle)
        FreezeWatchdog(::onFreeze).start()
        exec.execute {
            runCatching {
                val now = System.currentTimeMillis()
                recent.addAll(entries().filter { now - it.createdAt < ReportPolicy.AUTO_WINDOW_MS })
                cleanup()
            }.onFailure { Log.w(TAG, "reading reports failed", it) }
            if (Build.VERSION.SDK_INT >= 30) runCatching { ExitReasons.check(app, this) }.onFailure { Log.w(TAG, "exit reasons failed", it) }
        }
    }

    // ---------------------------------------------------------------- saving

    /** What's on screen now. Main thread. [screenshot] false skips screen.png. */
    fun capture(activity: Activity?, screenshot: Boolean = true): Capture {
        val screen = screenOf(activity, live = true)
        val s = (activity ?: currentActivity) as? Screen
        val key = runCatching { s?.reportStreamKey() }.getOrNull()
        val shot = if (screenshot && activity != null && !activity.isFinishing && !activity.isDestroyed) {
            runCatching { ScreenCapture.start(activity, s?.reportVideo()) }.onFailure { Log.w(TAG, "screenshot failed", it) }.getOrNull()
        } else null
        return Capture(screen, key, shot ?: CompletableFuture.completedFuture(null))
    }

    /**
     * Saves a report in the background; [onSaved] gets its folder (null when saving failed) on the
     * main thread. [capture] null = no screenshot, screen described by name only.
     */
    fun save(
        kind: String,
        detail: String?,
        capture: Capture?,
        note: String? = null,
        extra: Map<String, Any?> = emptyMap(),
        key: String? = null,
        stack: String? = null,
        onSaved: ((File?) -> Unit)? = null,
    ) {
        val time = System.currentTimeMillis()
        exec.execute {
            val shot = capture?.shot?.let { runCatching { it.get(4, TimeUnit.SECONDS) }.getOrNull() }
            val screen = capture?.screen ?: screenOf(null, live = false)
            val dir = runCatching { write(Report(kind, detail, note, screen, capture?.streamKey, extra, key, time, shot, stack)) }
                .onFailure { Log.w(TAG, "saving a $kind report failed", it) }
                .getOrNull()
            shot?.recycle()
            runCatching { cleanup() }
            onSaved?.let { done -> main.post { done(dir) } }
        }
    }

    /**
     * An automatic report: at most one per kind + [key] every 10 minutes ([ReportPolicy]). [capture] runs
     * only when the report is wanted (it may take a screenshot). False when skipped.
     */
    fun auto(
        kind: String,
        detail: String?,
        key: String?,
        extra: Map<String, Any?> = emptyMap(),
        stack: String? = null,
        capture: () -> Capture? = { null },
    ): Boolean {
        if (!installed) return false
        synchronized(recent) {
            val now = System.currentTimeMillis()
            if (!ReportPolicy.shouldAutoSave(recent, ReportPolicy.safeKind(kind), key, now)) {
                Log.i(TAG, "$kind report skipped (one already saved for $key in the last 10 min)")
                return false
            }
            recent += ReportEntry("", 0, now, ReportPolicy.safeKind(kind), key)
        }
        save(kind, detail, runCatching(capture).getOrNull(), extra = extra, key = key, stack = stack)
        return true
    }

    /** A manual report without a note (TV: no typing on a remote). Main thread. */
    fun saveManual(activity: Activity?, trigger: String, note: String? = null, onSaved: ((File?) -> Unit)? = null) {
        save(MANUAL, note?.takeIf { it.isNotBlank() } ?: "Manual report ($trigger)", capture(activity), note = note, extra = mapOf("trigger" to trigger), onSaved = onSaved)
    }

    /**
     * Phone: "Report a problem" with an optional one-line note, then a "Report saved" toast. The
     * screen is captured before the dialog opens. A plain android.app.AlertDialog works over both
     * the Compose and the View activities.
     */
    fun ask(activity: Activity, trigger: String) {
        if (dialogOpen || activity.isFinishing || activity.isDestroyed) return
        val capture = capture(activity)
        dialogOpen = true
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = "What went wrong? (optional)"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        var saving = false
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Report a problem")
            .setMessage("Saves this screen and the app's recent activity, so it can be fixed.")
            .setView(FrameLayout(activity).apply { setPadding(pad, 0, pad, 0); addView(input) })
            .setPositiveButton("Save") { _, _ ->
                saving = true
                val note = input.text.toString().trim().takeIf { it.isNotEmpty() }
                save(MANUAL, note ?: "Manual report ($trigger)", capture, note = note, extra = mapOf("trigger" to trigger)) { dir ->
                    Toast.makeText(app, if (dir != null) "Report saved" else "Couldn't save the report", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                dialogOpen = false
                if (!saving) capture.discard()
            }
            .show()
    }

    /** A report about a previous run (Android's exit record). Runs on the report thread. */
    internal fun saveExit(kind: String, detail: String, extra: Map<String, Any?>, exitAt: Long, stack: String?, files: Map<String, ByteArray>) {
        write(
            Report(
                kind, detail, null, mapOf("name" to "previous run"), null, extra, key = null,
                stack = stack, files = files, logPid = null, logEnd = exitAt, engine = false,
            ),
        )
    }

    /** Did our own crash handler save a report for the crash Android recorded at [at]? */
    internal fun hasCrashNear(at: Long): Boolean =
        entries().any { it.kind == ReportPolicy.CRASH && it.createdAt in (at - 30_000)..(at + 5_000) }

    // ---------------------------------------------------------------- automatic triggers

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { saveCrash(thread, error) }
            // Android's handler logs the crash and shows the dialog; without one, die as it would.
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Synchronous (the process dies right after) and quick: no screenshot, which would need the main thread. */
    private fun saveCrash(thread: Thread, error: Throwable) {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val stack = buildString {
            append("Thread \"").append(thread.name).append("\"\n").append(error.stackTraceToString())
            if (!onMain) append("\nMain thread:\n").append(stackText(Looper.getMainLooper().thread.stackTrace))
        }
        val root = generateSequence(error) { it.cause }.last()
        val extra = mapOf(
            "exception" to error.javaClass.name, "message" to error.message, "thread" to thread.name,
            "rootCause" to if (root !== error) "${root.javaClass.name}: ${root.message}" else null,
        )
        val key = runCatching { (currentActivity as? Screen)?.reportStreamKey() }.getOrNull()
        write(Report(ReportPolicy.CRASH, "${error.javaClass.simpleName}: ${error.message}", null, screenOf(null, live = onMain), key, extra, key = null, stack = stack))
    }

    /** From [FreezeWatchdog]: the main thread hasn't answered for [blockedMs]. */
    private fun onFreeze(blockedMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val stack = buildString {
            append("Main thread (blocked ${blockedMs} ms):\n").append(stackText(mainThread.stackTrace))
            Thread.getAllStackTraces().filterKeys { it !== mainThread }.toList().sortedBy { it.first.name }.forEach { (t, st) ->
                append("\nThread \"").append(t.name).append("\" ").append(t.state).append('\n').append(stackText(st.take(20).toTypedArray()))
            }
        }
        val name = currentActivity?.javaClass?.simpleName
        val key = runCatching { (currentActivity as? Screen)?.reportStreamKey() }.getOrNull()
        auto("freeze", "Main thread blocked for ${blockedMs / 1000} s", key = name, extra = mapOf("blockedMs" to blockedMs, "streamKey" to key), stack = stack)
    }

    private fun onShake() {
        val a = currentActivity ?: return
        if (a.isInPictureInPictureMode || a.isFinishing) return
        Log.i(TAG, "shake on ${a.javaClass.simpleName}")
        ask(a, "shake")
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            // Phones only (TVs have no accelerometer); listening only while one of ours is on screen.
            if (!app.isTv) shake.start()
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity === activity) shake.stop()
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity === activity) resumed = null
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    // ---------------------------------------------------------------- writing

    private fun screenOf(activity: Activity?, live: Boolean): Map<String, Any?> {
        val a = activity ?: currentActivity
        val out = linkedMapOf<String, Any?>("name" to (a?.javaClass?.simpleName ?: "none"))
        if (a != null) out += mapOf("pip" to a.isInPictureInPictureMode, "finishing" to a.isFinishing)
        if (live && a is Screen) runCatching { out += a.reportState() }.onFailure { out["stateError"] = it.toString() }
        return out
    }

    /**
     * Writes the folder as `<name>.tmp` and renames it when complete, so a pull never sees half a
     * report. Returns the folder.
     */
    private fun write(r: Report): File? {
        val root = dir ?: return null.also { Log.w(TAG, "no external files dir: ${r.kind} report not saved") }
        val zone = ZoneId.systemDefault()
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.time), zone)
        val base = ReportPolicy.folderName(local, r.kind)
        var name = base
        var n = 2
        while (File(root, name).exists()) name = "$base-${n++}"
        val tmp = File(root, "$name.tmp").apply { deleteRecursively(); mkdirs() }
        val files = ArrayList<String>()

        val logEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.logEnd), zone)
        val logs = try {
            LogWindow.filter(readLogs(r.logPid), logEnd, LOG_WINDOW_SEC, listOf(BuildConfig.TMDB_TOKEN))
        } catch (t: Throwable) {
            listOf("(reading logcat failed: $t)")
        }
        File(tmp, "logs.txt").writeText(logs.joinToString("\n", postfix = "\n"))
        files += "logs.txt"
        r.stack?.let { File(tmp, "stack.txt").writeText(it); files += "stack.txt" }
        r.files.forEach { (file, bytes) -> File(tmp, file).writeBytes(bytes); files += file }
        r.shot?.let { shot ->
            if (runCatching { ScreenCapture.writePng(shot, File(tmp, "screen.png")) }.onFailure { Log.w(TAG, "screen.png failed", it) }.getOrDefault(false)) files += "screen.png"
        }

        val report = linkedMapOf(
            "kind" to r.kind,
            "key" to r.key,
            "time" to OffsetDateTime.ofInstant(Instant.ofEpochMilli(r.time), zone).toString(),
            "epochMs" to r.time,
            "detail" to r.detail,
            "note" to r.note,
            "app" to mapOf("versionName" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE, "debug" to BuildConfig.DEBUG),
            "device" to device(),
            "screen" to r.screen,
            "trigger" to r.extra,
            "engine" to if (r.engine) engineState(r.streamKey) else null,
            "flags" to mapOf(
                "inapp" to EngineFlags.inapp, "zone" to EngineFlags.zone, "grace" to EngineFlags.grace,
                "govern" to EngineFlags.govern, "verboseStats" to EngineFlags.verboseStats,
            ),
            "reelStats" to LogWindow.messagesFor(logs, EngineStats.TAG, logEnd).takeLast(STATS_LINES),
            "files" to files + "report.json",
        )
        File(tmp, "report.json").writeText(json.encodeToString(JsonElement.serializer(), toJson(report)))
        val done = File(root, name)
        val out = if (tmp.renameTo(done)) done else tmp
        Log.i(TAG, "ReelReport saved ${out.absolutePath}")
        return out
    }

    private fun device(): Map<String, Any?> {
        val rt = Runtime.getRuntime()
        val dm = app.resources.displayMetrics
        return mapOf(
            "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER, "sdk" to Build.VERSION.SDK_INT,
            "abi" to Build.SUPPORTED_ABIS.firstOrNull(), "formFactor" to if (app.isTv) "tv" else "phone",
            "display" to "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi",
            "heapUsedMb" to (rt.totalMemory() - rt.freeMemory()) / MB, "heapMaxMb" to rt.maxMemory() / MB,
        )
    }

    /** The streams running now (and the screen's own one), without starting the engine. */
    private fun engineState(streamKey: String?): Map<String, Any?>? {
        val engine = app.engineIfCreated ?: return null
        val keys = (listOfNotNull(streamKey) + engine.streamingKeys()).distinct()
        return mapOf("streams" to keys.map { runCatching { engine.reportState(it) }.getOrElse { e -> mapOf("key" to it, "error" to e.toString()) } })
    }

    /** `logcat -d` of our own logs (apps may read their own); never waits more than a few seconds. */
    private fun readLogs(pid: Int?): List<String> {
        val cmd = arrayListOf("logcat", "-d", "-v", "threadtime", "-t", "4000")
        if (pid != null) cmd += "--pid=$pid"
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val killer = Thread {
            try { Thread.sleep(LOGCAT_TIMEOUT_MS); p.destroy() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try {
            return p.inputStream.bufferedReader().readLines()
        } finally {
            killer.interrupt()
            p.destroy()
        }
    }

    /** Old reports go (newest 40 / 80 MB; on the TV 20 / 20 MB), and half-written ones from a process that died saving. */
    private fun cleanup() {
        val root = dir ?: return
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { it.isDirectory && it.name.endsWith(".tmp") && now - it.lastModified() > 10 * 60_000L }?.forEach { it.deleteRecursively() }
        val limits = if (app.isTv) ReportPolicy.TV_MAX_COUNT to ReportPolicy.TV_MAX_BYTES else ReportPolicy.MAX_COUNT to ReportPolicy.MAX_BYTES
        ReportPolicy.toDelete(entries(), limits.first, limits.second).forEach {
            File(root, it.name).deleteRecursively()
            Log.i(TAG, "deleted old report ${it.name}")
        }
        recent.removeAll { now - it.createdAt > ReportPolicy.AUTO_WINDOW_MS }
    }

    /** Saved reports (not half-written ones), with kind and key from their report.json. */
    private fun entries(): List<ReportEntry> {
        val root = dir ?: return emptyList()
        return root.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".tmp") }?.mapNotNull { d ->
            val kind = ReportPolicy.kindOf(d.name) ?: return@mapNotNull null
            val meta = runCatching { Json.parseToJsonElement(File(d, "report.json").readText()).jsonObject }.getOrNull()
            ReportEntry(
                name = d.name,
                sizeBytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                createdAt = meta?.get("epochMs")?.jsonPrimitive?.longOrNull ?: d.lastModified(),
                kind = kind,
                key = meta?.get("key")?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
    }

    companion object {
        const val TAG = "ReelReport"
        const val DIR = "reports"
        const val MANUAL = "manual"
        private const val LOG_WINDOW_SEC = 120L
        private const val LOGCAT_TIMEOUT_MS = 4_000L
        private const val STATS_LINES = 60
        private const val MB = 1024 * 1024

        internal fun stackText(st: Array<StackTraceElement>): String = st.joinToString("") { "    at $it\n" }

        /** report.json without nulls (absent = unknown), so `jq` and `plutil` read every value. */
        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonPrimitive(v.toString())
            is Float -> if (v.isFinite()) JsonPrimitive(v) else JsonPrimitive(v.toString())
            is Number -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is Enum<*> -> JsonPrimitive(v.name)
            is Map<*, *> -> JsonObject(v.entries.filter { it.value != null }.associate { it.key.toString() to toJson(it.value) })
            is Iterable<*> -> JsonArray(v.filterNotNull().map(::toJson))
            is Array<*> -> JsonArray(v.filterNotNull().map(::toJson))
            else -> JsonPrimitive(v.toString())
        }
    }
}
