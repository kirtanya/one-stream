package com.kirtanya.reel.core.stream

/** What a stream is doing (docs/STREAMING_V2.md §4.2). */
enum class StreamPhase { PREWARM, STARTUP, PLAYING, BACKGROUND }

data class ReaderView(
    val id: Long,
    val kind: ReaderKind,
    val pos: Long,
    val openedAt: Long,
    val lastProgressAt: Long,
    val blockedPiece: Int = -1,
    val bytesRead: Long = 0,
    val superseded: Boolean = false,
    /** Closed less than ghostCloseMs ago with no open since: keeps the anchor (extractor seeks). */
    val ghost: Boolean = false,
)

data class PlaybackView(
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val playWhenReady: Boolean,
    val fresh: Boolean,
)

/** Metered networks fetch a bounded window; otherwise the whole file, in order. */
data class StorageAllowance(val fullFile: Boolean = true, val aheadCapBytes: Long = Long.MAX_VALUE, val metered: Boolean = false)

data class PlanInput(
    val span: FileSpan,
    val have: PieceSet,
    val profile: SchedulerProfile,
    val phase: StreamPhase,
    val download: Boolean,
    val readers: List<ReaderView>,
    val lastAnchor: Long?,
    val playback: PlaybackView?,
    val rate: MediaRate,
    val startup: StartupSet?,
    val startupDone: Boolean,
    val storage: StorageAllowance = StorageAllowance(),
    /** Priority the torrent's file choices give each piece (outside this file too). */
    val baseline: (Int) -> Int,
)

/** One stream's plan: deadlines and priorities for its file's pieces, plus a sequential range. */
data class Plan(
    val anchor: Long,
    val primaryId: Long?,
    /** Torrent piece → ms from now. */
    val deadlines: Map<Int, Int>,
    val sentinel: Int?,
    /** Indexed by piece - span.firstPiece. */
    val prio: ByteArray,
    val seq: IntRange?,
    val aheadEndPiece: Int,
)

/**
 * Plans what libtorrent should fetch for one stream (docs/STREAMING_V2.md §4.3).
 *
 * - An urgent zone right after the reader gets deadlines timed against the playhead, but only
 *   inside libtorrent's time-critical horizon (or when a reader is blocked on it): a small,
 *   stable critical list.
 * - Everything ahead is fetched in file order by a sequential range starting at the first
 *   missing piece, so spare bandwidth always extends the contiguous read-ahead.
 * - A "sentinel" (the last missing piece of the read-ahead) keeps the critical list non-empty,
 *   so libtorrent never cancels in-flight requests on a normal tick.
 * - Before the first frame, the header and the container index get the deadlines.
 */
object ZoneScheduler {
    const val PRIO_LOW = 1
    const val PRIO_NORMAL = 4
    const val PRIO_HIGH = 6
    const val PRIO_TOP = 7

    fun plan(i: PlanInput): Plan {
        val span = i.span
        val L = span.pieceLength
        val B = maxOf(i.rate.bytesPerSec, 128 * KiB)
        val P = i.profile
        val first = span.firstPiece
        val nPieces = span.lastPiece - first + 1
        val live = i.readers.filter { !it.superseded }
        val prim = live.filter { it.kind == ReaderKind.BENCH || (it.kind == ReaderKind.IN_APP && i.playback?.fresh == true && i.playback.playWhenReady) }
            .maxByOrNull { it.openedAt }
            ?: live.filter { it.kind == ReaderKind.HTTP }.maxByOrNull { maxOf(it.openedAt, it.lastProgressAt) }
            ?: live.filter { it.kind == ReaderKind.IN_APP }.maxByOrNull { it.openedAt }
        val anchor = prim?.pos ?: i.lastAnchor ?: i.startup?.resumeGuess?.first ?: 0L
        val aP = span.pieceOf(anchor)
        val aheadEnd = if (i.storage.fullFile) span.lastPiece else span.pieceOf(minOf(span.size - 1, anchor + i.storage.aheadCapBytes))
        val H = playheadByte(i, prim, B)
        val horizon = P.tcHorizonMs(L, B)
        val deadlines = LinkedHashMap<Int, Int>()
        val prio = ByteArray(nPieces)
        fun setPrio(p: Int, v: Int) { if (p in first..span.lastPiece) prio[p - first] = v.toByte() }
        fun raise(p: Int, v: Int) { if (p in first..span.lastPiece && prio[p - first] < v) prio[p - first] = v.toByte() }
        val have = i.have

        val urgentBudget = maxOf(2L * L, minOf(B * P.urgentSec, P.urgentMaxBytes))
        val K = minOf(P.urgentMaxPieces, maxOf(P.urgentMinPieces, ((urgentBudget + L - 1) / L).toInt()))

        // ---- base priorities
        when {
            i.phase == StreamPhase.PREWARM -> Unit // all 0
            i.download -> prio.fill(PRIO_NORMAL.toByte()) // never shrink a download
            i.phase == StreamPhase.STARTUP -> {
                val headEnd = i.startup?.let { span.pieceOf(it.header.last) } ?: first
                for (p in (headEnd + 1)..minOf(span.lastPiece, headEnd + 2 * K)) setPrio(p, PRIO_NORMAL)
                for (p in aP..minOf(span.lastPiece, aP + 2 * K)) setPrio(p, PRIO_NORMAL)
            }
            else -> {
                val behindStart = span.pieceOf(maxOf(0, anchor - B * P.keepBehindSec))
                val behindPrio = if (i.storage.fullFile && P.formFactor == FormFactor.PHONE) PRIO_LOW else 0
                for (p in first until behindStart) setPrio(p, behindPrio)
                for (p in behindStart..aheadEnd) setPrio(p, PRIO_NORMAL)
            }
        }
        // Header and index stay wanted (the player re-reads them).
        i.startup?.let { s ->
            span.piecesForBytes(s.header.first, s.header.last + 1).forEach { raise(it, PRIO_NORMAL) }
            s.index?.let { ix -> span.piecesForBytes(ix.first, ix.last + 1).forEach { raise(it, PRIO_NORMAL) } }
        }

        // ---- startup set: header first, then the index, interleaved
        val active = i.phase == StreamPhase.STARTUP || i.phase == StreamPhase.PLAYING
        val startup = i.startup
        if (startup != null && !i.startupDone && active) {
            var k = 0
            for (p in span.piecesForBytes(startup.header.first, startup.header.last + 1)) if (!have.has(p)) { deadlines[p] = 50 * k++; setPrio(p, PRIO_TOP) }
            var j = 0
            startup.index?.let { ix ->
                for (p in span.piecesForBytes(ix.first, ix.last + 1)) if (!have.has(p)) { deadlines.putIfAbsent(p, 25 + 50 * j++); setPrio(p, PRIO_TOP) }
            }
            startup.resumeGuess?.let { rg ->
                if (prim == null || prim.pos < startup.header.last) for (p in span.piecesForBytes(rg.first, rg.last + 1)) if (!have.has(p)) raise(p, PRIO_HIGH)
            }
        }
        if (startup != null && i.phase == StreamPhase.PREWARM) {
            for (p in span.piecesForBytes(startup.header.first, startup.header.last + 1)) if (!have.has(p)) raise(p, PRIO_HIGH)
            startup.index?.let { ix -> for (p in span.piecesForBytes(ix.first, ix.last + 1)) if (!have.has(p)) raise(p, PRIO_HIGH) }
        }

        // ---- urgent zone of the primary reader
        if (prim != null && active) {
            var n = 0
            var bytes = 0L
            var p = aP
            var last = -1
            val pb = i.playback
            while (p <= span.lastPiece && n < P.urgentMaxPieces && (n < P.urgentMinPieces || bytes < urgentBudget)) {
                if (!have.has(p)) {
                    var d = when {
                        p == prim.blockedPiece -> 0
                        H != null -> ((span.byteStartOf(p) - H) * 1000 / B - P.safetyMs).coerceIn(0, P.maxDeadlineMs.toLong()).toInt()
                        else -> n * P.ladderStepMs(L, B)
                    }
                    // Only a real pause (the user's) relaxes deadlines. Starting up and rebuffering are not
                    // playing either, and that is exactly when the pieces ahead are needed soonest.
                    if (pb != null && !pb.playWhenReady && p != prim.blockedPiece) d = maxOf(d, P.pausedDeadlineFloorMs)
                    d = maxOf(d, last)
                    if (p == prim.blockedPiece || d <= horizon) {
                        deadlines.merge(p, d, ::minOf)
                        setPrio(p, PRIO_TOP)
                        last = d
                    } else raise(p, PRIO_HIGH)
                }
                n++; bytes += L; p++
            }
        }

        // ---- sentinel: the last missing piece of the read-ahead keeps the critical list non-empty
        var sentinel: Int? = null
        if (prim != null && active) {
            val s = have.lastMissing(aP, aheadEnd)
            if (s >= 0 && s !in deadlines) { deadlines[s] = P.maxDeadlineMs; setPrio(s, PRIO_TOP); sentinel = s }
        }

        // ---- secondary readers (at most 3, newest first)
        for (r in (live - listOfNotNull(prim)).filter { !it.ghost }.sortedByDescending { maxOf(it.openedAt, it.lastProgressAt) }.take(3)) {
            val q = span.pieceOf(r.pos)
            if (r.kind == ReaderKind.HTTP && r.blockedPiece >= 0 && !have.has(r.blockedPiece)) {
                deadlines.merge(r.blockedPiece, 0, ::minOf); setPrio(r.blockedPiece, PRIO_TOP)
            } else for (x in q..minOf(span.lastPiece, q + 1)) if (!have.has(x)) raise(x, PRIO_HIGH)
        }

        // ---- edge pieces shared with other wanted files never drop below their baseline
        for (x in setOf(first, span.lastPiece)) raise(x, i.baseline(x))
        for (x in deadlines.keys) setPrio(x, PRIO_TOP)

        val seq = if (i.phase == StreamPhase.PLAYING || i.phase == StreamPhase.BACKGROUND) {
            have.firstMissing(aP, aheadEnd).takeIf { it >= 0 }?.let { it..aheadEnd }
        } else null
        return Plan(anchor, prim?.id, deadlines, sentinel, prio, seq, aheadEnd)
    }

    /** Where the playhead is in the file, anchored at the reader (VBR-tolerant), or null. */
    internal fun playheadByte(i: PlanInput, prim: ReaderView?, B: Long): Long? {
        val pb = i.playback ?: return null
        if (!pb.fresh) return null
        if (prim != null && prim.kind == ReaderKind.IN_APP && pb.bufferedPositionMs >= pb.positionMs) {
            return maxOf(0, prim.pos - (pb.bufferedPositionMs - pb.positionMs) * B / 1000)
        }
        if (i.rate.known && pb.durationMs > 0) return i.span.size * pb.positionMs / pb.durationMs
        return null
    }
}

/** A prefetch / prewarm request merged into a torrent's plan: torrent piece → priority. */
data class Overlay(val owner: Long, val targets: Map<Int, Int>)

/** The piece kept wanted so a claimed stream torrent never reaches "finished" (seeds would drop us). */
data class GuardState(val piece: Int, val pickedAtMs: Long, val extendedBytes: Long)

data class MergedPlan(
    val anchor: Long?,
    val deadlines: Map<Int, Int>,
    val sentinel: Int?,
    /** Whole torrent, one priority per piece. */
    val vector: ByteArray,
    val seq: IntRange?,
    val guard: GuardState?,
    val finishedAllowed: Boolean,
)

object PlanMerge {
    /**
     * Merges a stream's plan with the torrent's baseline and overlays (docs/STREAMING_V2.md §4.5a),
     * and keeps one missing piece wanted while a STREAM torrent is claimed.
     */
    fun merge(
        primary: Plan?,
        primarySpan: FileSpan?,
        overlays: List<Overlay>,
        baseline: (Int) -> Int,
        have: PieceSet,
        numPieces: Int,
        streamMode: Boolean,
        prevGuard: GuardState?,
        pieceLength: Int,
        rate: MediaRate,
        nowMs: Long,
    ): MergedPlan {
        val vector = ByteArray(numPieces) { q ->
            val own = if (primary != null && primarySpan != null && q in primarySpan.firstPiece..primarySpan.lastPiece) {
                primary.prio[q - primarySpan.firstPiece].toInt()
            } else baseline(q)
            maxOf(own, overlays.maxOfOrNull { it.targets[q] ?: 0 } ?: 0).toByte()
        }
        primary?.deadlines?.keys?.forEach { if (it in 0 until numPieces) vector[it] = ZoneScheduler.PRIO_TOP.toByte() }

        var guard: GuardState? = null
        var finishedAllowed = false
        if (streamMode) {
            val anyWanted = (0 until numPieces).any { q -> vector[q] > 0 && !have.has(q) && q != prevGuard?.piece }
            if (!anyWanted) {
                val B = maxOf(rate.bytesPerSec, 128 * KiB)
                val repickMs = maxOf(10_000L, 2L * pieceLength * 1000 / B)
                val maxBytes = maxOf(2L * pieceLength, 32 * MiB)
                when {
                    prevGuard != null && !have.has(prevGuard.piece) -> {
                        guard = prevGuard
                        if (vector[prevGuard.piece] < 1) vector[prevGuard.piece] = 1
                    }
                    (prevGuard == null || nowMs - prevGuard.pickedAtMs >= repickMs) && (prevGuard?.extendedBytes ?: 0) + pieceLength <= maxBytes -> {
                        val lastWanted = (numPieces - 1 downTo 0).firstOrNull { vector[it] > 0 } ?: -1
                        val g = ((lastWanted + 1) until numPieces).firstOrNull { !have.has(it) }
                            ?: (0 until numPieces).firstOrNull { !have.has(it) }
                        if (g != null) {
                            if (vector[g] < 1) vector[g] = 1
                            guard = GuardState(g, nowMs, (prevGuard?.extendedBytes ?: 0) + pieceLength)
                        } else finishedAllowed = true
                    }
                    else -> finishedAllowed = true
                }
            }
        }
        return MergedPlan(primary?.anchor, primary?.deadlines.orEmpty(), primary?.sentinel, vector, primary?.seq, guard, finishedAllowed)
    }
}

/** What we last told libtorrent, so the next plan only sends the differences. */
data class AppliedState(
    /** Torrent piece → absolute due time (elapsed ms). */
    val dueAt: Map<Int, Long> = emptyMap(),
    val vector: ByteArray? = null,
    val seq: IntRange? = null,
    val seqAtMs: Long = 0,
    val anchor: Long? = null,
    val sentinel: Int? = null,
)

data class DiffContext(
    val nowMs: Long,
    val forceFull: Boolean,
    /** A supersede (season-pack switch): clear the old stream's deadlines once. */
    val forceClear: Boolean,
    val have: PieceSet,
    val profile: SchedulerProfile,
    val numPieces: Int,
    val farSeekBytes: Long,
)

sealed interface PlanOp {
    data object ClearDeadlines : PlanOp
    data class SetDeadline(val piece: Int, val ms: Int) : PlanOp
    data class ResetDeadline(val piece: Int) : PlanOp
    /** Sparse, torrent-indexed. */
    data class SetPriorities(val changes: List<Pair<Int, Int>>) : PlanOp
    data class SetAllPriorities(val vector: ByteArray) : PlanOp
    data class SetSequential(val first: Int, val last: Int) : PlanOp
    data object UnsetSequential : PlanOp
}

object PlanDiff {
    /**
     * The libtorrent calls that take [prev] to [plan] (docs/STREAMING_V2.md §4.5b). New and moved
     * deadlines go first, departed ones after, so libtorrent's critical list never empties on a
     * normal tick (an empty list makes it cancel every in-flight request). A far seek clears once.
     */
    fun diff(prev: AppliedState?, plan: MergedPlan, ctx: DiffContext): Pair<List<PlanOp>, AppliedState> {
        val ops = ArrayList<PlanOp>()
        val P = ctx.profile
        val have = ctx.have
        val full = prev == null || ctx.forceFull
        val far = ctx.forceClear || (prev?.anchor != null && plan.anchor != null && kotlin.math.abs(plan.anchor - prev.anchor) > ctx.farSeekBytes)
        val vector = (prev?.vector?.takeIf { it.size == ctx.numPieces }?.copyOf() ?: ByteArray(ctx.numPieces))
        val dueAt = HashMap(prev?.dueAt.orEmpty())
        if (far && dueAt.isNotEmpty()) {
            ops += PlanOp.ClearDeadlines
            for (p in dueAt.keys) if (!have.has(p) && p in vector.indices) vector[p] = 1
            dueAt.clear()
        }
        // 1. adds and moves first
        val nextDue = HashMap<Int, Long>()
        for ((p, ms) in plan.deadlines) {
            if (have.has(p)) continue
            val abs = ctx.nowMs + ms
            val old = dueAt[p]
            if (p == plan.sentinel && p == prev?.sentinel && old != null && old - ctx.nowMs > P.maxDeadlineMs / 2) {
                nextDue[p] = old
                continue
            }
            if (full || old == null || kotlin.math.abs(abs - old) > P.deadlineSlackMs) {
                ops += PlanOp.SetDeadline(p, ms)
                if (p in vector.indices) vector[p] = ZoneScheduler.PRIO_TOP.toByte()
                nextDue[p] = abs
            } else nextDue[p] = old
        }
        // 2. removals (had pieces leave libtorrent's list by themselves)
        for (p in dueAt.keys) if (p !in plan.deadlines && !have.has(p)) {
            ops += PlanOp.ResetDeadline(p)
            if (p in vector.indices) vector[p] = 1
        }
        // 3. priorities
        if (full) {
            ops += PlanOp.SetAllPriorities(plan.vector.copyOf())
        } else {
            val changes = ArrayList<Pair<Int, Int>>()
            for (q in 0 until ctx.numPieces) if (!have.has(q) && plan.vector[q] != vector[q]) changes += q to plan.vector[q].toInt()
            if (changes.size > maxOf(2_048, ctx.numPieces / 4)) ops += PlanOp.SetAllPriorities(plan.vector.copyOf())
            else if (changes.isNotEmpty()) ops += PlanOp.SetPriorities(changes)
        }
        // 4. sequential range
        var seqAt = prev?.seqAtMs ?: 0
        val seq = plan.seq
        if (seq == null) {
            if (prev?.seq != null) ops += PlanOp.UnsetSequential
        } else if (full || prev?.seq == null || seq.first != prev.seq.first ||
            kotlin.math.abs(seq.last - prev.seq.last) >= P.seqMoveMinPieces || ctx.nowMs - prev.seqAtMs >= P.seqReassertMs
        ) {
            ops += PlanOp.SetSequential(seq.first, seq.last)
            seqAt = ctx.nowMs
        }
        return ops to AppliedState(nextDue, plan.vector.copyOf(), seq, seqAt, plan.anchor, plan.sentinel)
    }
}
