package com.kirtanya.reel.core.stream

/**
 * The player's state for the scheduler: written on the main thread (2 Hz while the player is
 * visible), read on the engine thread. No locks, no libtorrent calls on the playback thread.
 */
class PlayerSignals {
    @Volatile var positionMs = 0L
    @Volatile var bufferedPositionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var playWhenReady = false
    @Volatile var playing = false
    @Volatile var firstFrame = false
    @Volatile var updatedAtMs = 0L

    /** Stale after 3 s (player stopped, backgrounded or handed off). */
    fun fresh(nowMs: Long) = updatedAtMs > 0 && nowMs - updatedAtMs < 3_000

    fun view(nowMs: Long): PlaybackView? =
        if (updatedAtMs == 0L) null
        else PlaybackView(positionMs, bufferedPositionMs, durationMs, playing, playWhenReady, fresh(nowMs))
}
