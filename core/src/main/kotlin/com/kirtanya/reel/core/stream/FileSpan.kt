package com.kirtanya.reel.core.stream

/**
 * Where one file sits inside a torrent's piece space.
 * [offset] is the file's first byte within the torrent (all files laid end to end).
 *
 * All byte positions taken or returned here are file-relative. Piece edges are computed from
 * bytes, so a file that starts or ends part-way through a piece is handled exactly.
 */
data class FileSpan(
    val offset: Long,
    val size: Long,
    val pieceLength: Int,
    val numPieces: Int,
) {
    init {
        require(size >= 0 && offset >= 0 && pieceLength > 0 && numPieces > 0)
    }

    val firstPiece: Int get() = pieceOf(0)
    val lastPiece: Int get() = pieceOf(maxOf(0, size - 1))
    val pieceRange: IntRange get() = firstPiece..lastPiece

    /** The piece holding byte [filePos] of this file. */
    fun pieceOf(filePos: Long): Int =
        ((offset + filePos.coerceIn(0, maxOf(0, size - 1))) / pieceLength).toInt().coerceAtMost(numPieces - 1)

    /** All pieces that overlap [range] (positions within this file). */
    fun piecesFor(range: ByteRange): IntRange = pieceOf(range.start)..pieceOf(range.endInclusive)

    /** Pieces overlapping file bytes [from, toExclusive). Empty when the range is empty or the file is. */
    fun piecesForBytes(from: Long, toExclusive: Long): IntRange {
        val a = from.coerceAtLeast(0)
        val b = toExclusive.coerceAtMost(size)
        if (size == 0L || a >= b) return IntRange.EMPTY
        return pieceOf(a)..pieceOf(b - 1)
    }

    /** First file byte inside [piece] (0 for the piece holding the file's start). */
    fun byteStartOf(piece: Int): Long = (piece.toLong() * pieceLength - offset).coerceIn(0, size)

    /** One past the last file byte inside [piece]. */
    fun byteEndOf(piece: Int): Long = ((piece + 1).toLong() * pieceLength - offset).coerceIn(0, size)

    /** How many pieces make up roughly [bytes] of data (at least 1). */
    fun piecesForBytes(bytes: Long): Int = maxOf(1, ((bytes + pieceLength - 1) / pieceLength).toInt())
}
