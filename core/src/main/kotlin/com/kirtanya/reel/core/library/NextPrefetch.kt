package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.homedisk.P2pRule
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.pickNextSource
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.stream.CachePolicy
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.GiB

/** Why the next episode is not being downloaded (docs/STREAMING_V2.md §7.7); [tag] goes into ReelStats. */
enum class PrefetchWhy(val tag: String) {
    OFF("off"),
    NOT_EPISODE("not-episode"),
    LAST_EPISODE("last-episode"),
    RECENT_TRY("recent-try"),
    ALREADY_HAVE("already-have"),
    NETWORK("network"),
    NIGHT_WINDOW("night-window"),
    BUFFER("buffer"),
    NO_SOURCE("no-source"),
    NOT_TORRENT("not-torrent"),
    NO_ROOM("no-room"),
}

sealed interface PrefetchStep {
    /** Search for the next episode's source and download it. */
    data object Go : PrefetchStep

    /** Not now; the player asks again on its next tick (network, night window, buffer). */
    data class Wait(val why: PrefetchWhy) : PrefetchStep

    /** Nothing to do for this episode (setting off, a movie, the last episode, already downloaded). */
    data class Skip(val why: PrefetchWhy) : PrefetchStep
}

/** Everything the decision reads, gathered by the app. */
data class PrefetchFacts(
    val enabled: Boolean,
    /** The playing item is an episode of a TMDB show. */
    val isEpisode: Boolean,
    /** The show's TMDB id (null for a raw torrent file). */
    val tmdbId: Int?,
    /** [com.kirtanya.reel.core.playback.Playback.next]: null on the last aired episode. */
    val next: EpisodeNumber?,
    /** The engine's DOWNLOAD torrents with a known film / episode. */
    val downloads: List<KnownDownload>,
    /** How long ago a prefetch of this episode was last started (or failed); null = never in this run. */
    val lastTryAgoMs: Long?,
    /** The phone's network rule for a download right now; the TV passes Allow. */
    val downloadVerdict: P2pVerdict,
    /** The "download only overnight" window holds a new download right now. */
    val nightHolds: Boolean,
    val bufferHealthy: Boolean,
)

/** The source to fetch, or why there is none. */
sealed interface PrefetchPick {
    data class Chosen(val source: Source) : PrefetchPick
    data class None(val why: PrefetchWhy) : PrefetchPick
}

/**
 * "Download the next episode while watching" (docs/STREAMING_V2.md §7.7): when to start, which source,
 * whether it fits. Pure; the engine calls are in the app's NextEpisodePrefetcher.
 */
object NextPrefetch {
    /** The current episode must have this much buffered ahead of the playhead … */
    const val MIN_AHEAD_MS = 15_000L

    /** … continuously for this long, before anything else may use the link. */
    const val HOLD_MS = 30_000L

    /** A prefetch of the same episode is not tried again within this time. */
    const val RETRY_AFTER_MS = 6 * 60 * 60_000L

    /** Size assumed for a source that doesn't say (a season-pack file). */
    const val UNKNOWN_SIZE = 3 * GiB

    fun check(f: PrefetchFacts): PrefetchStep {
        if (!f.enabled) return PrefetchStep.Skip(PrefetchWhy.OFF)
        val show = f.tmdbId
        if (!f.isEpisode || show == null) return PrefetchStep.Skip(PrefetchWhy.NOT_EPISODE)
        val next = f.next ?: return PrefetchStep.Skip(PrefetchWhy.LAST_EPISODE)
        if (f.lastTryAgoMs != null && f.lastTryAgoMs < RETRY_AFTER_MS) return PrefetchStep.Skip(PrefetchWhy.RECENT_TRY)
        // Running or finished, whoever started it (the user, Auto-download, an earlier prefetch).
        if (AutoDownload.missing(show, listOf(next), f.downloads).isEmpty()) return PrefetchStep.Skip(PrefetchWhy.ALREADY_HAVE)
        if (!P2pRule.downloadsAllowed(f.downloadVerdict)) return PrefetchStep.Wait(PrefetchWhy.NETWORK)
        if (f.nightHolds) return PrefetchStep.Wait(PrefetchWhy.NIGHT_WINDOW)
        if (!f.bufferHealthy) return PrefetchStep.Wait(PrefetchWhy.BUFFER)
        return PrefetchStep.Go
    }

    /**
     * Autoplay-next's pick ([pickNextSource]: same binge group, then same provider and resolution…), minus torrents the
     * engine already holds ([heldHashes], lower case): adding a file to the pack being streamed would turn that stream
     * into a download. Only a torrent can be downloaded this way.
     */
    fun pick(previous: Source?, ranked: List<Source>, heldHashes: Set<String>): PrefetchPick {
        val candidates = ranked.filterNot { it.kind == SourceKind.TORRENT && hashOf(it) in heldHashes }
        val source = pickNextSource(previous, candidates) ?: return PrefetchPick.None(PrefetchWhy.NO_SOURCE)
        if (source.kind != SourceKind.TORRENT || source.magnetUri() == null) return PrefetchPick.None(PrefetchWhy.NOT_TORRENT)
        return PrefetchPick.Chosen(source)
    }

    /** Free space after the reserve covers the file; nothing is evicted for a prefetch. */
    fun fits(source: Source, freeBytes: Long, formFactor: FormFactor): Boolean =
        freeBytes - CachePolicy.hardFloor(formFactor) >= (source.sizeBytes ?: UNKNOWN_SIZE)

    /** The torrent's info-hash, lower case (null when the source carries no usable one). */
    fun hashOf(s: Source): String? = (s.infoHash ?: s.magnet?.let { Magnet.parse(it)?.infoHash })?.lowercase()
}

/**
 * Is the stream that is playing now healthy enough to share the link? Fed with the player's state a few times
 * a minute; healthy once [NextPrefetch.MIN_AHEAD_MS] has been buffered continuously for [NextPrefetch.HOLD_MS]
 * (a stall, a seek that empties the buffer or leaving READY starts the wait again).
 */
class BufferWatch(
    private val minAheadMs: Long = NextPrefetch.MIN_AHEAD_MS,
    private val holdMs: Long = NextPrefetch.HOLD_MS,
) {
    private var since: Long? = null

    fun sample(nowMs: Long, ready: Boolean, aheadMs: Long) {
        if (ready && aheadMs >= minAheadMs) {
            if (since == null) since = nowMs
        } else {
            since = null
        }
    }

    fun healthy(nowMs: Long): Boolean = since?.let { nowMs - it >= holdMs } == true
}
