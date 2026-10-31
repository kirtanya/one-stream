package com.kirtanya.reel.engine

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.launcher.StopEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Stopping an idle engine (One Launcher's "Speed up", and on the TV by itself after
 * [StopEngine.IDLE_STOP_MS]) and starting it early (`warm_engine`), so Play doesn't wait.
 */
object EngineIdle {
    private const val TAG = "ReelEngineIdle"
    private const val CHECK_MS = 30_000L

    /** What [StopEngine] says about the engine right now. */
    fun verdict(app: ReelApp): StopEngine.Verdict {
        val list = app.engineIfCreated?.torrents?.value.orEmpty()
        val downloads = list.filter { it.mode == TorrentMode.DOWNLOAD && !it.userPaused }
        return StopEngine.decide(
            visibleScreens = app.startedActivities.get(),
            streams = list.count { it.streaming },
            downloads = downloads.count {
                it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA || it.phase == TorrentPhase.CHECKING
            },
            waiting = downloads.count { it.waitingForNight },
        )
    }

    /**
     * Saves resume data, stops the foreground service and ends the process (as Quit does), so the next
     * start is a fresh engine. Runs on its own thread: saving waits ~1.5 s.
     */
    fun stop(app: ReelApp, why: String) {
        val engine = app.engineIfCreated
        Thread({
            runCatching { engine?.shutdown() }
            runCatching { app.stopService(Intent(app, EngineService::class.java)) }
            Log.i(TAG, "engine stopped: $why")
            Thread.sleep(300)
            Process.killProcess(Process.myPid())
        }, "reel-stop-engine").start()
    }

    /** Starts the engine off the main thread and logs how long it took (process start shows in logcat as "Start proc"). */
    fun warm(app: ReelApp, why: String) {
        app.appScope.launch {
            val wasCreated = app.engineIfCreated != null
            val t0 = SystemClock.elapsedRealtime()
            runCatching { app.engine.start() }.onFailure { Log.w(TAG, "warm failed: ${it.message}") }
            Log.i(TAG, "engine warm ($why): ${if (wasCreated) "already running" else "started in ${SystemClock.elapsedRealtime() - t0} ms"}")
        }
    }

    /** TV only: once something starts the engine, stop it after [StopEngine.IDLE_STOP_MS] with nothing in use. */
    fun watch(app: ReelApp) {
        app.appScope.launch {
            app.engineCreated.first { it }
            var idleSince: Long? = null
            while (true) {
                delay(CHECK_MS)
                val (since, stopNow) = StopEngine.idleCheck(verdict(app), idleSince, SystemClock.elapsedRealtime())
                idleSince = since
                if (stopNow) {
                    stop(app, "idle ${StopEngine.IDLE_STOP_MS / 60_000} min")
                    return@launch
                }
            }
        }
    }
}
