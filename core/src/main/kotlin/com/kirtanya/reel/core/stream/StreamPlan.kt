package com.kirtanya.reel.core.stream

/** A piece the engine should fetch, and how soon (ms from now). */
data class PieceDeadline(val piece: Int, val deadlineMs: Int)

/**
 * Decides which pieces to ask for, and in what order, while a file is being streamed.
 *
 * - [startup]: the first bytes (container header) and the last bytes (MP4 `moov` / MKV cues
 *   often live at the end) so the player can start and seek.
 * - [window]: a read-ahead window starting at the player's current position. Called again
 *   on every seek or as playback moves forward.
 */
class StreamPlanner(
    private val span: FileSpan,
    private val headBytes: Long = 8L * MB,
    private val tailBytes: Long = 4L * MB,
    private val stepMs: Int = 150,
) {
    fun startup(): List<PieceDeadline> {
        val head = span.firstPiece until (span.firstPiece + span.piecesForBytes(headBytes)).coerceAtMost(span.lastPiece + 1)
        val tailStart = (span.lastPiece - span.piecesForBytes(tailBytes) + 1).coerceAtLeast(span.firstPiece)
        val tail = tailStart..span.lastPiece
        val ordered = LinkedHashSet<Int>()
        // Interleave: first head piece, then the tail, then the rest of the head.
        head.firstOrNull()?.let(ordered::add)
        tail.forEach(ordered::add)
        head.forEach(ordered::add)
        return ordered.mapIndexed { i, piece -> PieceDeadline(piece, i * stepMs) }
    }

    /**
     * Read-ahead window from [filePos]. [windowBytes] is how much to keep ahead of the
     * playhead (roughly 30–60 s of video).
     */
    fun window(filePos: Long, windowBytes: Long): List<PieceDeadline> {
        val from = span.pieceOf(filePos)
        val to = (from + span.piecesForBytes(windowBytes) - 1).coerceAtMost(span.lastPiece)
        return (from..to).mapIndexed { i, piece -> PieceDeadline(piece, i * stepMs) }
    }

    companion object {
        const val MB = 1024L * 1024L

        /**
         * Read-ahead size from the file's bitrate: about [seconds] of video, kept between
         * 16 MB and 96 MB. Falls back to 32 MB when the duration is unknown.
         */
        fun windowBytes(fileSize: Long, durationSec: Long?, seconds: Int = 45): Long {
            if (durationSec == null || durationSec <= 0) return 32 * MB
            val bytesPerSec = fileSize / durationSec
            return (bytesPerSec * seconds).coerceIn(16 * MB, 96 * MB)
        }
    }
}
