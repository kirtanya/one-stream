package com.kirtanya.reel.core.stream

/** What the engine knows about a torrent when the user converts it (docs/STREAMING_V2.md §7.3a). */
data class ModeFacts(
    val isDownload: Boolean,
    /** A player is reading it or starting on it (an ACTIVE / Starting claim). */
    val claimed: Boolean,
    val keepPending: Boolean = false,
    val cachePending: Boolean = false,
)

/** When a conversion runs. */
enum class ModeStep {
    /** Already in the asked-for mode (and nothing pending the other way). */
    NONE,

    /** Run it now. */
    NOW,

    /** A player holds it: remember the wish (persisted) and run it when the stream stops. */
    DEFER,

    /** Undo a pending conversion the other way; the torrent is already in the asked-for mode. */
    CANCEL_PENDING,
}

/**
 * "Save as download" (stream cache → Downloads, the existing Keep) and "Keep as stream cache"
 * (Downloads → stream cache) as pure decisions. The later wish always wins over a pending one.
 */
object ModeChange {
    fun toDownload(f: ModeFacts): ModeStep = when {
        f.isDownload && f.cachePending -> ModeStep.CANCEL_PENDING
        f.isDownload -> ModeStep.NONE
        f.claimed -> ModeStep.DEFER
        else -> ModeStep.NOW
    }

    fun toCache(f: ModeFacts): ModeStep = when {
        !f.isDownload && f.keepPending -> ModeStep.CANCEL_PENDING
        !f.isDownload -> ModeStep.NONE
        f.claimed -> ModeStep.DEFER
        else -> ModeStep.NOW
    }

    /**
     * The file a cache entry remembers for Play: the stream file if one was set, else the first
     * wanted file, else whatever the snapshot showed. Null = pick the main video on next play.
     */
    fun cacheStreamFile(streamFileIndex: Int?, wantedFiles: List<Int>, snapshotFile: Int?): Int? =
        streamFileIndex ?: wantedFiles.minOrNull() ?: snapshotFile

    /**
     * A converted download should be treated as just used: it isn't the first thing the next trim
     * deletes, but it isn't protected like a title stopped mid-watch either (§7.4 protection uses
     * lastStoppedAt, which this leaves alone).
     */
    fun cacheLastUsedAt(now: Long, previous: Long): Long = maxOf(now, previous)

    /** The "Keep as stream cache?" dialog body: what happens to the file and the download. */
    fun cacheConfirm(finished: Boolean): String =
        (if (finished) "" else "Downloading stops. ") +
            "What's downloaded stays on this device as stream cache, so it still plays, and is deleted automatically when space is needed."
}
