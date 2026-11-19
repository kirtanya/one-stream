package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.StoreEntry
import com.kirtanya.reel.core.homedisk.UsbStore
import com.kirtanya.reel.core.homedisk.UsbTest
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [UsbStore] on a temp folder with a 100-byte piece, so splitting, joining and power-cut cases take microseconds. */
class UsbStoreTest {
    private val root: File = Files.createTempDirectory("usbstore").toFile()
    private val piece = 100L
    private val store = UsbStore(root, piece)

    @AfterTest fun clean() {
        root.deleteRecursively()
    }

    private fun bytes(n: Int, seed: Int = 1) = Random(seed).nextBytes(n)

    private fun put(path: String, data: ByteArray, chunk: Int = 37) {
        if (data.isEmpty()) store.write(path, 0, 0, data)
        var at = 0
        while (at < data.size) {
            val n = minOf(chunk, data.size - at)
            store.write(path, data.size.toLong(), at.toLong(), data, at, n)
            at += n
        }
        store.flush(path)
        store.commit(path)
    }

    private fun readAll(path: String, step: Int = 29): ByteArray {
        val size = store.size(path)!!.toInt()
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = store.read(path, at.toLong(), out, at, minOf(step, size - at))
            assertTrue(n > 0, "read at $at gave $n")
            at += n
        }
        return out
    }

    private fun names(dir: String = "") = File(root, dir).list()!!.sorted()

    @Test fun `a small file is one plain file`() {
        val data = bytes(60)
        put("a/small.mkv", data)
        assertEquals(listOf("small.mkv"), names("a"))
        assertEquals(60L, store.size("a/small.mkv"))
        assertContentEquals(data, readAll("a/small.mkv"))
    }

    @Test fun `a file of exactly one piece is not split`() {
        put("one.mkv", bytes(100))
        assertEquals(listOf("one.mkv"), names())
    }

    @Test fun `a bigger file is split into numbered pieces and read back as one stream`() {
        val data = bytes(250)
        put("m/film.mkv", data)
        assertEquals(listOf("film.mkv.001", "film.mkv.002", "film.mkv.003"), names("m"))
        assertEquals(listOf(100L, 100L, 50L), names("m").map { File(root, "m/$it").length() })
        assertEquals(250L, store.size("m/film.mkv"))
        assertContentEquals(data, readAll("m/film.mkv"))
        assertContentEquals(data, readAll("m/film.mkv", step = 1000))
        assertContentEquals(data, readAll("m/film.mkv", step = 7))
    }

    @Test fun `a file of one piece plus one byte has a one byte last piece`() {
        val data = bytes(101)
        put("x.mkv", data)
        assertEquals(listOf("x.mkv.001", "x.mkv.002"), names())
        assertEquals(1L, File(root, "x.mkv.002").length())
        assertContentEquals(data, readAll("x.mkv"))
    }

    @Test fun `a read across a boundary is seekable and returns the right bytes`() {
        val data = bytes(300)
        put("s.mkv", data)
        val buf = ByteArray(30)
        assertEquals(30, store.read("s.mkv", 85, buf))
        assertContentEquals(data.copyOfRange(85, 115), buf)
        assertEquals(30, store.read("s.mkv", 185, buf))
        assertContentEquals(data.copyOfRange(185, 215), buf)
    }

    @Test fun `a read near the end is short and past the end is minus one`() {
        val data = bytes(250)
        put("e.mkv", data)
        val buf = ByteArray(100)
        assertEquals(10, store.read("e.mkv", 240, buf))
        assertContentEquals(data.copyOfRange(240, 250), buf.copyOf(10))
        assertEquals(-1, store.read("e.mkv", 250, buf))
        assertEquals(-1, store.read("e.mkv", 9999, buf))
        assertEquals(-1, store.read("nope.mkv", 0, buf))
    }

    @Test fun `a read with an offset into the buffer fills only its part`() {
        val data = bytes(250)
        put("o.mkv", data)
        val buf = ByteArray(20) { 9 }
        assertEquals(10, store.read("o.mkv", 95, buf, 5, 10))
        assertContentEquals(data.copyOfRange(95, 105), buf.copyOfRange(5, 15))
        assertEquals(9, buf[0])
        assertEquals(9, buf[19])
    }

    @Test fun `writes may arrive out of order and across boundaries`() {
        val data = bytes(250)
        store.write("r.mkv", 250, 150, data, 150, 100)
        store.write("r.mkv", 250, 0, data, 0, 60)
        store.write("r.mkv", 250, 60, data, 60, 90)
        store.flush("r.mkv")
        store.commit("r.mkv")
        assertContentEquals(data, readAll("r.mkv"))
    }

    @Test fun `nothing is visible until commit`() {
        val data = bytes(250)
        store.write("p.mkv", 250, 0, data, 0, 250)
        store.flush("p.mkv")
        assertNull(store.size("p.mkv"))
        assertFalse(store.exists("p.mkv"))
        assertEquals(emptyList<StoreEntry>(), store.list(""))
        assertEquals(listOf("p.mkv.001.part", "p.mkv.002.part", "p.mkv.003.part"), names())
    }

    @Test fun `a part file can be read while it is being written`() {
        val data = bytes(250)
        store.write("w.mkv", 250, 120, data, 120, 60)
        store.flush("w.mkv")
        val buf = ByteArray(60)
        assertEquals(60, store.read("w.mkv", 120, buf, part = true))
        assertContentEquals(data.copyOfRange(120, 180), buf)
        // Piece 001 hasn't been written at all yet: no bytes, not a crash.
        assertEquals(-1, store.read("w.mkv", 0, buf, part = true))
    }

    @Test fun `a small part file is read as one file`() {
        val data = bytes(40)
        store.write("t.bin", 40, 0, data, 0, 40)
        store.flush("t.bin")
        val buf = ByteArray(40)
        assertEquals(40, store.read("t.bin", 0, buf, part = true))
        assertContentEquals(data, buf)
        assertNull(store.size("t.bin"))
    }

    @Test fun `commit twice is safe`() {
        val data = bytes(150)
        put("c.mkv", data)
        store.commit("c.mkv")
        assertContentEquals(data, readAll("c.mkv"))
    }

    @Test fun `a cut during commit never lists half a file`() {
        val data = bytes(250)
        put("cut.mkv", data)
        // Rewind to what disk looks like if the power went after the last piece was renamed but before the others:
        File(root, "cut.mkv.001").renameTo(File(root, "cut.mkv.001.part"))
        File(root, "cut.mkv.002").renameTo(File(root, "cut.mkv.002.part"))
        assertEquals(emptyList<StoreEntry>(), store.list(""))
        assertNull(store.size("cut.mkv"))
        // The next start commits again and the file is whole.
        store.commit("cut.mkv")
        assertEquals(listOf(StoreEntry("cut.mkv", false, 250)), store.list(""))
        assertContentEquals(data, readAll("cut.mkv"))
    }

    @Test fun `a missing middle piece makes the set incomplete`() {
        put("gap.mkv", bytes(250))
        File(root, "gap.mkv.002").delete()
        assertNull(store.size("gap.mkv"))
        assertEquals(emptyList<StoreEntry>(), store.list(""))
    }

    @Test fun `a short non-last piece makes the set incomplete`() {
        put("short.mkv", bytes(250))
        File(root, "short.mkv.001").writeBytes(ByteArray(90))
        assertNull(store.size("short.mkv"))
    }

    @Test fun `a set whose last piece is empty is incomplete`() {
        put("zero.mkv", bytes(250))
        File(root, "zero.mkv.003").writeBytes(ByteArray(0))
        assertNull(store.size("zero.mkv"))
    }

    @Test fun `an empty file is a plain empty file`() {
        put("empty.txt", ByteArray(0))
        assertEquals(0L, store.size("empty.txt"))
        assertEquals(-1, store.read("empty.txt", 0, ByteArray(4)))
    }

    @Test fun `list shows files once at logical size, folders, and hides parts`() {
        put("d/a.mkv", bytes(250))
        put("d/b.txt", bytes(5))
        store.mkdirs("d/sub")
        store.write("d/c.mkv", 250, 0, bytes(250), 0, 250)
        assertEquals(
            listOf(StoreEntry("a.mkv", false, 250), StoreEntry("b.txt", false, 5), StoreEntry("sub", true, 0)),
            store.list("d"),
        )
        assertEquals(emptyList<StoreEntry>(), store.list("missing"))
    }

    @Test fun `exists is true for a committed file and a folder`() {
        put("f/x.mkv", bytes(250))
        assertTrue(store.exists("f"))
        assertTrue(store.exists("f/x.mkv"))
        assertFalse(store.exists("f/y.mkv"))
    }

    @Test fun `delete removes every piece and part of a file and leaves its neighbours`() {
        put("g/x.mkv", bytes(250))
        put("g/xy.mkv", bytes(250))
        store.write("g/x.mkv", 250, 0, bytes(10), 0, 10)
        store.delete("g/x.mkv")
        assertEquals(listOf("xy.mkv.001", "xy.mkv.002", "xy.mkv.003"), names("g"))
        store.delete("g/never-there.mkv")
    }

    @Test fun `delete removes a folder with everything in it`() {
        put("h/a/x.mkv", bytes(250))
        store.delete("h")
        assertFalse(File(root, "h").exists())
    }

    @Test fun `rename moves every piece and replaces what was there`() {
        val data = bytes(250)
        put("a/old.mkv", data)
        put("b/new.mkv", bytes(120, seed = 5))
        store.rename("a/old.mkv", "b/new.mkv")
        assertEquals(emptyList<String>(), names("a"))
        assertEquals(listOf("new.mkv.001", "new.mkv.002", "new.mkv.003"), names("b"))
        assertContentEquals(data, readAll("b/new.mkv"))
    }

    @Test fun `rename onto a shorter file leaves no stale pieces`() {
        put("big.mkv", bytes(250))
        put("small.mkv", bytes(30))
        store.rename("small.mkv", "big.mkv")
        assertEquals(listOf("big.mkv"), names())
        assertEquals(30L, store.size("big.mkv"))
    }

    @Test fun `rename creates the target folder`() {
        put("x.bin", bytes(10))
        store.rename("x.bin", "deep/er/y.bin")
        assertEquals(10L, store.size("deep/er/y.bin"))
    }

    @Test fun `rename of a folder moves it whole`() {
        put("one/x.mkv", bytes(250))
        store.rename("one", "two")
        assertContentEquals(File(root, "two/x.mkv.001").readBytes().copyOf(100), File(root, "two/x.mkv.001").readBytes())
        assertEquals(250L, store.size("two/x.mkv"))
        assertFalse(File(root, "one").exists())
    }

    @Test fun `rename of a missing file fails loudly`() {
        assertFailsWith<java.io.IOException> { store.rename("nope", "x") }
    }

    @Test fun `a path can't leave the disk folder`() {
        assertFailsWith<IllegalArgumentException> { store.size("../outside") }
        assertFailsWith<IllegalArgumentException> { store.delete("a/../../x") }
        assertFailsWith<IllegalArgumentException> { store.write("./x", 1, 0, byteArrayOf(1)) }
    }

    @Test fun `a write outside the declared size is refused`() {
        assertFailsWith<IllegalArgumentException> { store.write("x", 10, 8, ByteArray(5)) }
    }

    @Test fun `free and total space come from the folder, and are null when it is gone`() {
        assertNotNull(store.freeBytes())
        assertTrue(store.totalBytes()!! >= store.freeBytes()!!)
        val gone = UsbStore(File(root, "unplugged"))
        assertNull(gone.freeBytes())
        assertEquals(emptyList<StoreEntry>(), gone.list(""))
        assertNull(gone.size("x"))
    }

    @Test fun `the disk Test passes on a plain folder and cleans up after itself`() {
        val facts = UsbTest.run(root)
        assertNull(facts.error, facts.toString())
        assertEquals(true, facts.hashOk)
        assertEquals(true, facts.splitOk)
        assertTrue(UsbTest.succeeded(facts))
        assertTrue(UsbTest.line(facts).startsWith("Ready · USB"), UsbTest.line(facts))
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test fun `the disk Test reports a folder it can't write to`() {
        val file = File(root, "not-a-folder").also { it.writeText("x") }
        val facts = UsbTest.run(file)
        assertNotNull(facts.error)
        assertFalse(UsbTest.succeeded(facts))
        assertTrue(UsbTest.line(facts).startsWith("Couldn't write"), UsbTest.line(facts))
    }

    @Test fun `the boundary check passes with a tiny piece size`() {
        val facts = UsbTest.big(root, piece = 3L * 1024 * 1024)
        assertTrue(facts.ok, facts.detail)
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }
}
