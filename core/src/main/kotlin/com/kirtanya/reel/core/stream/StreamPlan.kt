package com.kirtanya.reel.core.stream

/** A piece the engine should fetch, and how soon (ms from now). */
data class PieceDeadline(val piece: Int, val deadlineMs: Int)

/**
 * Interim planner (replaced by the zone scheduler in phase 3 of docs/STREAMING_V2.md).
 *
 * - [startup]: the first bytes (container header) and the last bytes (MP4 `moov` / MKV cues
 *   often live at the end) so the player can start and seek.
 * - [window]: a read-ahead window starting at the player's current position.
 *
 * Ranges are computed from bytes, so a file that starts or ends part-way through a piece still
 * gets the head / tail bytes it needs (the old piece-count maths could cover only a few KB).
 */
class StreamPlanner(
    private val span: FileSpan,
    private val headBytes: Long = 8L * MiB,
    private val tailBytes: Long = 4L * MiB,
    private val stepMs: Int = 150,
) {
    fun startup(): List<PieceDeadline> {
        if (span.size == 0L) return emptyList()
        val head = span.piecesForBytes(0, minOf(span.size, headBytes))
        val tail = span.piecesForBytes(maxOf(0, span.size - tailBytes), span.size)
        val ordered = LinkedHashSet<Int>()
        // Interleave: first head piece, then the tail, then the rest of the head.
        head.firstOrNull()?.let(ordered::add)
        tail.forEach(ordered::add)
        head.forEach(ordered::add)
        return ordered.mapIndexed { i, piece -> PieceDeadline(piece, i * stepMs) }
    }

    /** Read-ahead window: every piece holding a byte of [filePos, filePos + windowBytes). */
    fun window(filePos: Long, windowBytes: Long): List<PieceDeadline> {
        if (span.size == 0L) return emptyList()
        val from = filePos.coerceIn(0, span.size - 1)
        val pieces = span.piecesForBytes(from, from + windowBytes.coerceAtLeast(1))
        return pieces.mapIndexed { i, piece -> PieceDeadline(piece, i * stepMs) }
    }

    companion object {
        /**
         * Read-ahead size from the media rate: about [seconds] of video, at least 4 pieces and 32 MiB,
         * capped at [capBytes]. Falls back to 32 MiB when the rate is unknown.
         */
        fun windowBytes(bytesPerSec: Long?, pieceLength: Int, seconds: Int = 60, capBytes: Long = 512 * MiB): Long {
            val floor = maxOf(4L * pieceLength, 32 * MiB)
            if (bytesPerSec == null || bytesPerSec <= 0) return floor.coerceAtMost(capBytes)
            return (bytesPerSec * seconds).coerceIn(floor.coerceAtMost(capBytes), capBytes)
        }
    }
}
