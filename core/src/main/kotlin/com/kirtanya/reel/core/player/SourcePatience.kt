package com.kirtanya.reel.core.player

import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind

/** What the engine reports for the stream being started, once a second ([elapsedMs] = since the user asked to play). */
data class StartSample(
    val elapsedMs: Long,
    val hasMetadata: Boolean,
    val peers: Int,
    val downBps: Long,
    /** Bytes of the wanted file(s) on disk; growing means data is arriving. */
    val doneBytes: Long,
)

/** Why a source is about to be left. */
enum class GiveUpReason(val text: String) {
    NO_INFO("Couldn't get this file's info"),
    NO_DATA("Nothing is downloading"),
}

enum class PatienceAction { WAIT, COUNTDOWN, SWITCH }

/**
 * [findAlternates]: the source is close to being given up on, so look for another one now (it takes seconds, and the
 * countdown only shows when there is one to go to).
 */
data class PatienceVerdict(
    val action: PatienceAction,
    val secondsLeft: Int = 0,
    val reason: GiveUpReason? = null,
    val findAlternates: Boolean = false,
)

/**
 * When the player may give up on a torrent source that hasn't started and move to another one (docs/STREAMING_V2.md
 * §7.6, DECISIONS.md 2026-11-19 "Be patient with a starting source").
 *
 * A source that is downloading at all is never left for being slow: bytes arriving (or the file's data growing)
 * resets the clock. Only two things count as dead: no metadata after [noInfoMs] ([noInfoWithPeersMs] when peers are
 * connected, because they are still handing the info over), or metadata with nothing arriving for [noDataMs]
 * ([noDataWithPeersMs] with peers connected). Before leaving, a [COUNTDOWN_MS] countdown shows; "Keep waiting"
 * holds the decision for [KEEP_WAITING_MS]. Pure: the caller feeds one [StartSample] a second.
 */
class SourcePatience(
    private val noInfoMs: Long = NO_INFO_MS,
    private val noInfoWithPeersMs: Long = NO_INFO_WITH_PEERS_MS,
    private val noDataMs: Long = NO_DATA_MS,
    private val noDataWithPeersMs: Long = NO_DATA_WITH_PEERS_MS,
) {
    private var metaAtMs: Long? = null
    private var lastProgressAtMs: Long? = null
    private var lastDone = 0L
    private var countdownFromMs: Long? = null
    private var holdUntilMs = 0L

    /** The user asked to wait: no countdown until [KEEP_WAITING_MS] from [nowMs]. */
    fun keepWaiting(nowMs: Long) {
        holdUntilMs = nowMs + KEEP_WAITING_MS
        countdownFromMs = null
    }

    /**
     * [canSwitch]: another source is ready to take over (and this is the start, not a rebuffer mid-film, and the
     * hop limit isn't reached). [searchable]: switching is allowed in principle, so alternates may be looked up.
     */
    fun update(s: StartSample, canSwitch: Boolean, searchable: Boolean = canSwitch): PatienceVerdict {
        if (s.hasMetadata && metaAtMs == null) metaAtMs = s.elapsedMs
        // Bytes arriving, or the file's data growing, is progress. Before the info there is nothing to download.
        if (s.hasMetadata && (s.downBps > 0 || s.doneBytes > lastDone)) lastProgressAtMs = s.elapsedMs
        lastDone = maxOf(lastDone, s.doneBytes)

        val base = if (!s.hasMetadata) 0L else lastProgressAtMs ?: metaAtMs ?: 0L
        val limit = when {
            !s.hasMetadata -> if (s.peers > 0) noInfoWithPeersMs else noInfoMs
            else -> if (s.peers > 0) noDataWithPeersMs else noDataMs
        }
        val reason = if (s.hasMetadata) GiveUpReason.NO_DATA else GiveUpReason.NO_INFO
        val idleMs = s.elapsedMs - base
        val held = s.elapsedMs < holdUntilMs
        val dead = !held && idleMs >= limit
        val lookNow = searchable && !held && idleMs >= limit - ALTERNATES_LEAD_MS

        if (!dead || !canSwitch) {
            countdownFromMs = null
            return PatienceVerdict(PatienceAction.WAIT, findAlternates = lookNow)
        }
        val from = countdownFromMs ?: s.elapsedMs.also { countdownFromMs = it }
        val left = COUNTDOWN_MS - (s.elapsedMs - from)
        return if (left <= 0) PatienceVerdict(PatienceAction.SWITCH, reason = reason, findAlternates = true)
        else PatienceVerdict(PatienceAction.COUNTDOWN, secondsLeft = ((left + 999) / 1000).toInt(), reason = reason, findAlternates = true)
    }

    companion object {
        /** No metadata, no peers: 25 s (the first engine-level idea was 8 s, which gave up on slow trackers). */
        const val NO_INFO_MS = 25_000L
        /** No metadata but peers connected: they are handing it over, 40 s. */
        const val NO_INFO_WITH_PEERS_MS = 40_000L
        /** Metadata, nothing arriving, no peers. */
        const val NO_DATA_MS = 30_000L
        /** Metadata, nothing arriving, peers connected but choking us. */
        const val NO_DATA_WITH_PEERS_MS = 45_000L
        const val COUNTDOWN_MS = 5_000L
        /** "Keep waiting" holds the decision this long. */
        const val KEEP_WAITING_MS = 30_000L
        /** Other sources are searched for this long before the countdown could start. */
        const val ALTERNATES_LEAD_MS = 10_000L

        /** Non-breaking spaces keep "5 s" together when the line wraps at a large font. */
        fun countdownText(secondsLeft: Int) = "Trying another source in\u00A0$secondsLeft\u00A0s"
    }
}

/**
 * Which torrents may take over from one that never started: another torrent (a different info hash) of the same
 * film / episode, not one already tried, not a known dead swarm (0 seeders listed), not a cam rip, and not a
 * higher resolution (a bigger file starts slower). The ranker's order is kept.
 */
object TorrentFallback {
    /** Automatic hand-overs in a row, then the player just waits (and "Other sources" stays). */
    const val MAX_AUTO_SWITCHES = 2

    fun candidates(current: Source, ranked: List<Source>, tried: Set<String>, skip: (Source) -> Boolean = { false }): List<Source> =
        ranked.filter {
            it.kind == SourceKind.TORRENT && !it.isExtra && it.infoHash != null && it.infoHash != current.infoHash &&
                it.key !in tried && it.key != current.key && it.seeders != 0 && !it.quality.lowQualityRip && !skip(it) &&
                (it.quality.resolution ?: 0) <= (current.quality.resolution ?: Int.MAX_VALUE)
        }

    fun pick(current: Source, ranked: List<Source>, tried: Set<String>, skip: (Source) -> Boolean = { false }): Source? =
        candidates(current, ranked, tried, skip).firstOrNull()

    /** The chip the next player shows for its first seconds. */
    fun switchNote(alt: Source): String = "That source wasn't starting. Playing ${BufferInfoBuilder.facts(alt)} instead"
}
