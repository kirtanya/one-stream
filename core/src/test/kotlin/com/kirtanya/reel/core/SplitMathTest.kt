package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.SplitMath
import com.kirtanya.reel.core.homedisk.SplitMath.Run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitMathTest {
    private val p = 100L

    @Test fun `the piece is under the FAT32 limit`() {
        assertTrue(SplitMath.PIECE_SIZE < 4L * 1024 * 1024 * 1024 - 1)
        assertEquals(4095L * 1024 * 1024, SplitMath.PIECE_SIZE)
    }

    @Test fun `piece count rounds up and an empty file is one piece`() {
        assertEquals(1, SplitMath.pieceCount(0, p))
        assertEquals(1, SplitMath.pieceCount(1, p))
        assertEquals(1, SplitMath.pieceCount(100, p))
        assertEquals(2, SplitMath.pieceCount(101, p))
        assertEquals(2, SplitMath.pieceCount(200, p))
        assertEquals(3, SplitMath.pieceCount(201, p))
    }

    @Test fun `only a file bigger than one piece is split`() {
        assertFalse(SplitMath.isSplit(100, p))
        assertTrue(SplitMath.isSplit(101, p))
        assertFalse(SplitMath.isSplit(SplitMath.PIECE_SIZE))
        assertTrue(SplitMath.isSplit(SplitMath.PIECE_SIZE + 1))
    }

    @Test fun `piece sizes add up to the file and only the last is short`() {
        val size = 250L
        assertEquals(listOf(100L, 100L, 50L), (0 until 3).map { SplitMath.pieceSize(size, it, p) })
        assertEquals(size, (0 until 3).sumOf { SplitMath.pieceSize(size, it, p) })
        assertEquals(100L, SplitMath.pieceSize(200, 1, p))
        assertFailsWith<IllegalArgumentException> { SplitMath.pieceSize(250, 3, p) }
    }

    @Test fun `an offset maps to a piece and a local offset`() {
        assertEquals(0 to 0L, SplitMath.locate(0, p))
        assertEquals(0 to 99L, SplitMath.locate(99, p))
        assertEquals(1 to 0L, SplitMath.locate(100, p))
        assertEquals(2 to 37L, SplitMath.locate(237, p))
        assertFailsWith<IllegalArgumentException> { SplitMath.locate(-1, p) }
    }

    @Test fun `offsets past 4 GiB map correctly in the real piece size`() {
        val gib4 = 4L * 1024 * 1024 * 1024
        val (index, local) = SplitMath.locate(gib4)
        assertEquals(1, index)
        assertEquals(gib4 - SplitMath.PIECE_SIZE, local)
    }

    @Test fun `a read inside one piece is one run`() {
        assertEquals(listOf(Run(0, 10, 20)), SplitMath.runs(10, 20, 250, p))
        assertEquals(listOf(Run(1, 0, 100)), SplitMath.runs(100, 100, 250, p))
    }

    @Test fun `a read across a boundary is cut in two`() {
        assertEquals(listOf(Run(0, 90, 10), Run(1, 0, 15)), SplitMath.runs(90, 25, 250, p))
    }

    @Test fun `a read across two boundaries is cut in three`() {
        assertEquals(listOf(Run(0, 99, 1), Run(1, 0, 100), Run(2, 0, 5)), SplitMath.runs(99, 106, 250, p))
    }

    @Test fun `a read that ends exactly on a boundary does not touch the next piece`() {
        assertEquals(listOf(Run(0, 50, 50)), SplitMath.runs(50, 50, 250, p))
    }

    @Test fun `a read past the end is cut at the end`() {
        assertEquals(listOf(Run(2, 40, 10)), SplitMath.runs(240, 100, 250, p))
        assertEquals(listOf(Run(1, 90, 10), Run(2, 0, 50)), SplitMath.runs(190, 500, 250, p))
    }

    @Test fun `a read at or past the end, or of zero bytes, is nothing`() {
        assertEquals(emptyList(), SplitMath.runs(250, 10, 250, p))
        assertEquals(emptyList(), SplitMath.runs(900, 10, 250, p))
        assertEquals(emptyList(), SplitMath.runs(10, 0, 250, p))
    }

    @Test fun `runs always add up to the bytes asked for and stay in the file`() {
        val size = 1037L
        for (offset in listOf(0L, 1, 99, 100, 101, 555, 999, 1036)) {
            for (len in listOf(1, 7, 100, 101, 250, 5000)) {
                val runs = SplitMath.runs(offset, len, size, p)
                assertEquals(minOf(len.toLong(), size - offset), runs.sumOf { it.length.toLong() })
                var at = offset
                for (r in runs) {
                    assertEquals(at, r.piece * p + r.local)
                    assertTrue(r.local + r.length <= p)
                    at += r.length
                }
            }
        }
    }

    @Test fun `pieces are named from 001`() {
        assertEquals("movie.mkv.001", SplitMath.pieceName("movie.mkv", 0))
        assertEquals("movie.mkv.002", SplitMath.pieceName("movie.mkv", 1))
        assertEquals("movie.mkv.010", SplitMath.pieceName("movie.mkv", 9))
        assertEquals("movie.mkv.999", SplitMath.pieceName("movie.mkv", 998))
    }

    @Test fun `a piece name parses back to the file and the piece`() {
        assertEquals("movie.mkv" to 0, SplitMath.parsePiece("movie.mkv.001"))
        assertEquals("movie.mkv" to 11, SplitMath.parsePiece("movie.mkv.012"))
        assertNull(SplitMath.parsePiece("movie.mkv"))
        assertNull(SplitMath.parsePiece("movie.mkv.000"))
        assertNull(SplitMath.parsePiece("movie.mkv.01"))
        assertNull(SplitMath.parsePiece("movie.mkv.001.part"))
        assertNull(SplitMath.parsePiece(".001"))
    }
}
