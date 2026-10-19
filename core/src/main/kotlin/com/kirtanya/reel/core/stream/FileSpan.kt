package com.kirtanya.reel.core.stream

/**
 * Where one file sits inside a torrent's piece space.
 * [offset] is the file's first byte within the torrent (all files laid end to end).
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

    /** The piece holding byte [filePos] of this file. */
    fun pieceOf(filePos: Long): Int =
        ((offset + filePos.coerceIn(0, maxOf(0, size - 1))) / pieceLength).toInt().coerceAtMost(numPieces - 1)

    /** All pieces that overlap [range] (positions within this file). */
    fun piecesFor(range: ByteRange): IntRange = pieceOf(range.start)..pieceOf(range.endInclusive)

    /** How many pieces make up roughly [bytes] of data (at least 1). */
    fun piecesForBytes(bytes: Long): Int = maxOf(1, ((bytes + pieceLength - 1) / pieceLength).toInt())
}
