package com.kirtanya.reel.core.homedisk

/**
 * FAT32 holds at most 4 GiB − 1 bytes per file, so a bigger file is stored as numbered pieces
 * (`movie.mkv.001`, `.002`, …) and read back as one stream (docs/HOME_DISK.md §23.5). This is the offset
 * maths: logical offset → piece + local offset, piece sizes, and cutting a read at piece boundaries.
 * All pieces but the last have exactly [piece] bytes, so any offset maps with one division.
 */
object SplitMath {
    /** 1 MiB under the FAT32 maximum: some stacks (Windows tools, FUSE layers) mishandle a file of exactly the maximum. */
    const val PIECE_SIZE = 4095L * 1024 * 1024

    /** One contiguous read inside one piece. [piece] is 0-based; the file name carries piece + 1. */
    data class Run(val piece: Int, val local: Long, val length: Int)

    /** Size 0 is one empty whole file, so the count is never below 1. */
    fun pieceCount(size: Long, piece: Long = PIECE_SIZE): Int {
        require(size >= 0 && piece > 0)
        return if (size == 0L) 1 else ((size + piece - 1) / piece).toInt()
    }

    /** A file that fits in one piece is stored whole under its own name. */
    fun isSplit(size: Long, piece: Long = PIECE_SIZE) = size > piece

    fun pieceSize(size: Long, index: Int, piece: Long = PIECE_SIZE): Long {
        val n = pieceCount(size, piece)
        require(index in 0 until n) { "piece $index of $n" }
        return if (index < n - 1) piece else size - (n - 1) * piece
    }

    /** Logical offset → (piece index, offset inside it). */
    fun locate(offset: Long, piece: Long = PIECE_SIZE): Pair<Int, Long> {
        require(offset >= 0)
        return (offset / piece).toInt() to offset % piece
    }

    /** `[offset, offset + length)` cut at every piece boundary and at the end of the file (size), in order. */
    fun runs(offset: Long, length: Int, size: Long, piece: Long = PIECE_SIZE): List<Run> {
        require(offset >= 0 && length >= 0)
        if (offset >= size || length == 0) return emptyList()
        val end = minOf(size, offset + length)
        val out = ArrayList<Run>(2)
        var pos = offset
        while (pos < end) {
            val (index, local) = locate(pos, piece)
            val n = minOf(end - pos, piece - local).toInt()
            out += Run(index, local, n)
            pos += n
        }
        return out
    }

    /** Physical name of piece [index] (0-based): `movie.mkv.001` for the first. */
    fun pieceName(name: String, index: Int) = "$name.%03d".format(index + 1)

    /** The piece number in a physical name (`movie.mkv.002` → ("movie.mkv", 1)), or null when it isn't one. */
    fun parsePiece(physical: String): Pair<String, Int>? {
        val m = PIECE.matchEntire(physical) ?: return null
        val n = m.groupValues[2].toInt()
        return if (n >= 1) m.groupValues[1] to n - 1 else null
    }

    const val PART = ".part"
    private val PIECE = Regex("(.+)\\.(\\d{3})")
}
