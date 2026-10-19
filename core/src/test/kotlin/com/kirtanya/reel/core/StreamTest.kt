package com.kirtanya.reel.core

import com.kirtanya.reel.core.stream.ByteRange
import com.kirtanya.reel.core.stream.FileSpan
import com.kirtanya.reel.core.stream.StreamPlanner
import com.kirtanya.reel.core.stream.StreamPlanner.Companion.MB
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

    @Test fun windowBytesFromBitrate() {
        // 4 GB over 2 h ≈ 0.57 MB/s → 45 s ≈ 25 MB.
        val bytes = StreamPlanner.windowBytes(4L * 1024 * MB, 7200)
        assertTrue(bytes in 24 * MB..27 * MB)
        assertEquals(32 * MB, StreamPlanner.windowBytes(4L * 1024 * MB, null))
        assertEquals(16 * MB, StreamPlanner.windowBytes(100 * MB, 7200))
        assertEquals(96 * MB, StreamPlanner.windowBytes(80L * 1024 * MB, 3600))
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
