package com.kirtanya.reel.core.launcher

/**
 * One Launcher's "Speed up" on the TV asks One Stream to stop its idle engine (`stop_engine`).
 * Android 14 doesn't let a launcher stop other apps, so this is cooperative: One Stream stops only
 * when nothing of the user's would be cut short.
 */
object StopEngine {
    sealed interface Verdict {
        /** Nothing in use: save resume data, stop the foreground service, end the process. */
        data object Stop : Verdict
        data class Keep(val reason: String) : Verdict
    }

    /**
     * [visibleScreens]: One Stream activities started (on screen, PiP included). [streams]: torrents
     * being streamed (a player or an external player reading). [downloads]: downloads actively
     * transferring. [waiting]: downloads held for the night window (the night alarm reaches only a
     * running engine). Downloads the user started keep running: stopping them is the Downloads tab's job.
     */
    fun decide(visibleScreens: Int, streams: Int, downloads: Int, waiting: Int = 0): Verdict = when {
        visibleScreens > 0 -> Verdict.Keep("One Stream is open")
        streams > 0 -> Verdict.Keep("Something is playing")
        downloads > 0 -> Verdict.Keep(if (downloads == 1) "A download is running" else "$downloads downloads are running")
        waiting > 0 -> Verdict.Keep(if (waiting == 1) "A download waits for night" else "$waiting downloads wait for night")
        else -> Verdict.Stop
    }

    /**
     * On the TV the engine also stops by itself after this long with nothing in use, so an idle engine
     * doesn't hold ~80 MB all day. One Launcher wakes it (`warm_engine`) when a title page opens, so Play
     * still finds it running.
     */
    const val IDLE_STOP_MS = 10 * 60_000L

    /**
     * One idle check: [idleSince] is when nothing-in-use began (null = in use until now). Returns the new
     * idleSince and whether to stop now. Anything in use resets the clock.
     */
    fun idleCheck(verdict: Verdict, idleSince: Long?, now: Long, limitMs: Long = IDLE_STOP_MS): Pair<Long?, Boolean> =
        if (verdict != Verdict.Stop) null to false
        else (idleSince ?: now).let { since -> since to (now - since >= limitMs) }
}
