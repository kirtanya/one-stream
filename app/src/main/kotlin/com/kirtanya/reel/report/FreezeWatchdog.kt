package com.kirtanya.reel.report

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Notices a frozen screen: pings the main thread every second; when a ping goes unanswered for
 * [FREEZE_MS], calls [onFreeze] once for that freeze (it captures the main thread's stack while it
 * is still stuck). Uses uptime, which stops in deep sleep, so a sleeping phone isn't a freeze; a
 * debugger paused on a breakpoint isn't one either.
 */
internal class FreezeWatchdog(private val onFreeze: (blockedMs: Long) -> Unit) : Thread("reel-watchdog") {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var answered = true
    @Volatile private var sentAt = 0L
    @Volatile private var reported = false

    init {
        isDaemon = true
    }

    override fun run() {
        while (true) {
            try {
                sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = SystemClock.uptimeMillis()
            if (answered) {
                answered = false
                reported = false
                sentAt = now
                main.post {
                    val took = SystemClock.uptimeMillis() - sentAt
                    if (reported) Log.w(ReportCenter.TAG, "main thread answered again after $took ms")
                    answered = true
                }
            } else if (!reported && now - sentAt >= FREEZE_MS && !android.os.Debug.isDebuggerConnected()) {
                reported = true
                runCatching { onFreeze(now - sentAt) }.onFailure { Log.w(ReportCenter.TAG, "freeze report failed", it) }
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 1_000L
        const val FREEZE_MS = 5_000L
    }
}
