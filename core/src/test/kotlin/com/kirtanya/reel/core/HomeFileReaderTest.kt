package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.UsbStore
import com.kirtanya.reel.core.httpdl.HomeFileReader
import com.kirtanya.reel.core.httpdl.HomeFileRef
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Playing a finished direct download: one stream out of split pieces, seeking anywhere (docs/HOME_DISK.md §25.7). */
class HomeFileReaderTest {
    private val root: File = Files.createTempDirectory("homefile").toFile()
    private val data = Random(11).nextBytes(1000)

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun committed(pieceSize: Long): UsbStore {
        val store = UsbStore(root, pieceSize)
        store.write("a/movie.mkv", data.size.toLong(), 0, data)
        store.flush("a/movie.mkv")
        store.commit("a/movie.mkv")
        store.closeAll()
        return UsbStore(root, pieceSize)
    }

    @Test fun `a split file reads as one stream, across every piece boundary`() {
        val r = HomeFileReader(committed(300), "a/movie.mkv", block = 128)
        assertEquals(1000L, r.size)
        val out = ByteArray(1000)
        var pos = 0
        while (pos < 1000) {
            val n = r.read(pos.toLong(), out, pos, 77)
            assertEquals(minOf(77, 1000 - pos), n)
            pos += n
        }
        assertContentEquals(data, out)
        assertEquals(-1, r.read(1000, ByteArray(4), 0, 4))
    }

    @Test fun `seeking backwards and forwards returns the right bytes`() {
        val r = HomeFileReader(committed(300), "a/movie.mkv", block = 100)
        for (at in listOf(950, 3, 299, 300, 301, 598, 600, 0, 999, 450)) {
            val buf = ByteArray(40)
            val n = r.read(at.toLong(), buf, 0, 40)
            assertEquals(minOf(40, 1000 - at), n)
            assertContentEquals(data.copyOfRange(at, at + n), buf.copyOf(n), "at $at")
        }
    }

    @Test fun `a read longer than the block spans several blocks`() {
        val r = HomeFileReader(committed(300), "a/movie.mkv", block = 64)
        val buf = ByteArray(700)
        assertEquals(700, r.read(100, buf, 0, 700))
        assertContentEquals(data.copyOfRange(100, 800), buf)
    }

    @Test fun `an unsplit file and a missing file`() {
        val r = HomeFileReader(committed(Long.MAX_VALUE / 4), "a/movie.mkv", block = 256)
        val buf = ByteArray(10)
        assertEquals(10, r.read(995 - 5, buf, 0, 10))
        assertContentEquals(data.copyOfRange(990, 1000), buf)
        val gone = HomeFileReader(UsbStore(root, 300), "a/nope.mkv")
        assertNull(gone.size)
        assertEquals(-1, gone.read(0, buf, 0, 10))
    }

    @Test fun `the player's uri round trips the id and the name`() {
        val uri = HomeFileRef.format("0123456789ab", "My Film (2020) [1080p].mkv")
        assertEquals("0123456789ab", HomeFileRef.idOf(uri))
        assertEquals("My Film (2020) [1080p].mkv", HomeFileRef.nameOf(uri))
        assertNull(HomeFileRef.idOf("https://example.com/x.mkv"))
        assertNull(HomeFileRef.idOf("reel-file://nothex/x.mkv"))
    }
}
