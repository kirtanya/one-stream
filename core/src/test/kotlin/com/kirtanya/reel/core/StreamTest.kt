package com.kirtanya.reel.core

import com.kirtanya.reel.core.stream.ByteRange
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.StreamPlanner
import com.kirtanya.reel.core.stream.MB
import com.kirtanya.reel.core.stream.MiB
import com.kirtanya.reel.core.stream.TorrentFileEntry
import com.kirtanya.reel.core.stream.mimeTypeFor
import com.kirtanya.reel.core.stream.pickStreamFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteRangeTest {
    @Test fun missingHeaderMeansWholeFile() = assertEquals(ByteRange(0, 999), ByteRange.parse(null, 1000))
    @Test fun openEnded() = assertEquals(ByteRange(500, 999), ByteRange.parse("bytes=500-", 1000))
    @Test fun closed() = assertEquals(ByteRange(0, 99), ByteRange.parse("bytes=0-99", 1000))
    @Test fun endIsClamped() = assertEquals(ByteRange(900, 999), ByteRange.parse("bytes=900-5000", 1000))
    @Test fun suffix() = assertEquals(ByteRange(800, 999), ByteRange.parse("bytes=-200", 1000))
    @Test fun suffixLargerThanFile() = assertEquals(ByteRange(0, 999), ByteRange.parse("bytes=-5000", 1000))
    @Test fun multiRangeUsesFirst() = assertEquals(ByteRange(0, 9), ByteRange.parse("bytes=0-9, 20-29", 1000))
    @Test fun unsatisfiable() {
        assertNull(ByteRange.parse("bytes=1000-", 1000))
        assertNull(ByteRange.parse("bytes=50-10", 1000))
        assertNull(ByteRange.parse("items=0-1", 1000))
        assertNull(ByteRange.parse("bytes=0-1", 0))
    }
    @Test fun length() = assertEquals(100, ByteRange(0, 99).length)
}

class FileSpanTest {
    // Second file in a torrent: starts 1.5 pieces in, 1 MiB pieces.
    private val span = FileSpan(offset = 1_572_864, size = 10 * MB, pieceLength = 1_048_576, numPieces = 20)

    @Test fun firstAndLastPiece() {
        assertEquals(1, span.firstPiece)
        assertEquals(11, span.lastPiece)
    }

    @Test fun pieceOfClampsToFile() {
        assertEquals(1, span.pieceOf(-5))
        assertEquals(11, span.pieceOf(Long.MAX_VALUE))
    }

    @Test fun piecesForRange() = assertEquals(1..2, span.piecesFor(ByteRange(0, 600_000)))

    @Test fun piecesForBytesRoundsUp() {
        assertEquals(1, span.piecesForBytes(1))
        assertEquals(2, span.piecesForBytes(1_048_577))
    }
}

class StreamPlannerTest {
    private val span = FileSpan(offset = 0, size = 100 * MB, pieceLength = 1_048_576, numPieces = 100)
    private val planner = StreamPlanner(span, headBytes = 4 * MB, tailBytes = 2 * MB, stepMs = 100)

    @Test fun startupFetchesHeadAndTailFirst() {
        val plan = planner.startup().map { it.piece }
        assertEquals(listOf(0, 98, 99, 1, 2, 3), plan)
        assertEquals(listOf(0, 100, 200, 300, 400, 500), planner.startup().map { it.deadlineMs })
    }

    @Test fun windowFollowsPlayhead() {
        val w = planner.window(filePos = 50 * MB, windowBytes = 3 * MB)
        assertEquals(listOf(50, 51, 52), w.map { it.piece })
        assertEquals(0, w.first().deadlineMs)
    }

    @Test fun windowStopsAtLastPiece() {
        assertEquals(listOf(98, 99), planner.window(98 * MB, 10 * MB).map { it.piece })
    }

    @Test fun tinyFileStartupHasNoDuplicates() {
        val small = StreamPlanner(FileSpan(0, 500_000, 1_048_576, 1))
        assertEquals(listOf(0), small.startup().map { it.piece })
    }

    @Test fun windowBytesFromRate() {
        // 0.57 MiB/s × 60 s ≈ 34 MiB (above the 32 MiB floor).
        val bytes = StreamPlanner.windowBytes(600_000, pieceLength = 1_048_576)
        assertTrue(bytes in 34 * MB..35 * MB)
        assertEquals(32 * MB, StreamPlanner.windowBytes(null, pieceLength = 1_048_576))
        // Low bitrate: the floor, at least 4 pieces.
        assertEquals(64 * MB, StreamPlanner.windowBytes(100_000, pieceLength = 16 * MiB.toInt()))
        // 4K remux at 10 MiB/s: 60 s, capped at 512 MiB.
        assertEquals(512 * MB, StreamPlanner.windowBytes(10 * MiB, pieceLength = 16 * MiB.toInt()))
    }

    @Test fun unalignedTailCoversTheLastBytes() {
        // A file that ends 100 KB into its last 4 MiB piece: the tail must reach back into the
        // previous piece to cover the last 4 MiB.
        val size = 250L * 4 * MB + 100_000
        val p = StreamPlanner(FileSpan(0, size, 4 * MiB.toInt(), 251), headBytes = 8 * MB, tailBytes = 4 * MB)
        assertEquals(listOf(0, 249, 250, 1), p.startup().map { it.piece })
    }

    @Test fun unalignedHeadCoversTheFirstBytes() {
        // A second file starting 15.9 MiB into a 16 MiB piece: its first 8 MiB span two pieces.
        val pl = 16 * MiB.toInt()
        val span = FileSpan(offset = 15 * MB + 900 * 1024, size = 1000 * MB, pieceLength = pl, numPieces = 70)
        val p = StreamPlanner(span, headBytes = 8 * MB, tailBytes = 4 * MB)
        assertEquals(0, p.startup().first().piece)
        assertTrue(1 in p.startup().map { it.piece })
    }

    @Test fun emptyFilePlansNothing() {
        val p = StreamPlanner(FileSpan(0, 0, 1_048_576, 1))
        assertTrue(p.startup().isEmpty())
        assertTrue(p.window(0, 10 * MB).isEmpty())
    }
}

class VideoFilesTest {
    private val files = listOf(
        TorrentFileEntry(0, "Movie/Sample/movie-sample.mkv", 50 * MB),
        TorrentFileEntry(1, "Movie/movie.1080p.mkv", 2000 * MB),
        TorrentFileEntry(2, "Movie/movie.srt", 100_000),
        TorrentFileEntry(3, "Movie/extras/behind.mp4", 300 * MB),
    )

    @Test fun picksBiggestVideo() = assertEquals(1, pickStreamFile(files)!!.index)
    @Test fun honoursIndex() = assertEquals(3, pickStreamFile(files, fileIdx = 3)!!.index)
    @Test fun badIndexFallsBack() = assertEquals(1, pickStreamFile(files, fileIdx = 42)!!.index)
    @Test fun honoursFilenameHint() = assertEquals(3, pickStreamFile(files, filenameHint = "behind.mp4")!!.index)
    @Test fun skipsSamples() {
        val onlySampleIsBigger = listOf(
            TorrentFileEntry(0, "a/sample.mkv", 900 * MB),
            TorrentFileEntry(1, "a/film.mkv", 800 * MB),
        )
        assertEquals(1, pickStreamFile(onlySampleIsBigger)!!.index)
    }
    @Test fun noVideoMeansBiggestFile() {
        assertEquals(1, pickStreamFile(listOf(TorrentFileEntry(0, "a.txt", 1), TorrentFileEntry(1, "b.iso", 9)))!!.index)
    }
    @Test fun emptyIsNull() = assertNull(pickStreamFile(emptyList()))
    @Test fun mime() {
        assertEquals("video/x-matroska", mimeTypeFor("A.MKV"))
        assertEquals("video/mp4", mimeTypeFor("a.mp4"))
        assertEquals("application/octet-stream", mimeTypeFor("noext"))
    }
}
