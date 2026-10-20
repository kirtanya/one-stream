package com.kirtanya.reel.core.stream

/**
 * Scheduler constants for one device kind (docs/STREAMING_V2.md §4.1). Times in ms, sizes in bytes.
 */
data class SchedulerProfile(
    val formFactor: FormFactor,
    val disk: DiskBackend,
    /** Seconds of video ahead of the reader that are "urgent" (deadlines when inside the horizon). */
    val urgentSec: Int = 10,
    val urgentMinPieces: Int = 2,
    val urgentMaxPieces: Int = if (formFactor == FormFactor.TV) 12 else 16,
    val urgentMaxBytes: Long = if (formFactor == FormFactor.TV) 32 * MiB else 48 * MiB,
    /** Deadlines are set this much before the playhead reaches the piece. */
    val safetyMs: Int = if (formFactor == FormFactor.TV && disk == DiskBackend.POSIX) 3_000 else 2_000,
    /** While paused nothing is urgent. */
    val pausedDeadlineFloorMs: Int = 10_000,
    val maxDeadlineMs: Int = 60_000,
    val deadlineSlackMs: Int = 1_000,
    /** Seconds kept at normal priority behind the reader (D-pad rewinds). */
    val keepBehindSec: Int = if (formFactor == FormFactor.TV) 15 else 30,
    val seqReassertMs: Long = 5_000,
    val seqMoveMinPieces: Int = 8,
    val replanThrottleMs: Long = if (formFactor == FormFactor.TV) 500 else 250,
    val ghostCloseMs: Long = 500,
    val startupHeadBytes: Long = 2 * MiB,
    val startupExitMs: Long = 30_000,
) {
    /** How long one piece lasts at [bytesPerSec]. */
    fun pieceMs(pieceLength: Int, bytesPerSec: Long): Long = (pieceLength.toLong() * 1000 / bytesPerSec.coerceAtLeast(1)).coerceIn(100, 4_000)

    /** Pieces whose deadline lies inside this horizon get deadlines (and priority 7). */
    fun tcHorizonMs(pieceLength: Int, bytesPerSec: Long): Int = maxOf(3_000L, 2 * pieceMs(pieceLength, bytesPerSec)).toInt()

    /** Deadline step when there's no playhead (external players, bench). */
    fun ladderStepMs(pieceLength: Int, bytesPerSec: Long): Int = maxOf(250L, pieceMs(pieceLength, bytesPerSec) / 2).toInt()

    /** A jump bigger than this clears all deadlines once (frees peers busy with the old region). */
    fun farSeekBytes(urgentBytes: Long): Long = maxOf(64 * MiB, 2 * urgentBytes)

    fun startupTailBytes(extension: String): Long = if (extension == "ts" || extension == "m2ts") 1 * MiB else 4 * MiB

    companion object {
        fun of(formFactor: FormFactor, disk: DiskBackend) = SchedulerProfile(formFactor, disk)
    }
}

/** Where a media rate came from. Never from reader throughput (it reads cached data far faster than real time). */
enum class RateSource { PLAYER_DURATION, HINT_DURATION, GUESS }

/** Media bytes per second. */
data class MediaRate(val bytesPerSec: Long, val source: RateSource) {
    val known: Boolean get() = source != RateSource.GUESS

    companion object {
        fun of(size: Long, playerDurationMs: Long?, hintDurationMs: Long?): MediaRate = when {
            playerDurationMs != null && playerDurationMs > 0 -> MediaRate(size * 1000 / playerDurationMs, RateSource.PLAYER_DURATION)
            hintDurationMs != null && hintDurationMs > 0 -> MediaRate(size * 1000 / hintDurationMs, RateSource.HINT_DURATION)
            // A 90-minute guess, kept within sane bounds.
            else -> MediaRate((size / 5_400).coerceIn(256 * KiB, 10 * MiB), RateSource.GUESS)
        }
    }
}
