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
     * transferring. Downloads the user started keep running: stopping them is the Downloads tab's job.
     */
    fun decide(visibleScreens: Int, streams: Int, downloads: Int): Verdict = when {
        visibleScreens > 0 -> Verdict.Keep("One Stream is open")
        streams > 0 -> Verdict.Keep("Something is playing")
        downloads > 0 -> Verdict.Keep(if (downloads == 1) "A download is running" else "$downloads downloads are running")
        else -> Verdict.Stop
    }
}
