package com.kirtanya.reel.core.homedisk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

/** What the USB Test measured (null = that step didn't run). */
data class UsbTestFacts(
    val freeBytes: Long? = null,
    val writeMBps: Double? = null,
    val readMBps: Double? = null,
    val hashOk: Boolean? = null,
    val splitOk: Boolean? = null,
    val error: String? = null,
)

/** The real-boundary check's result: [ok] = every byte read across the first piece boundary was right. */
data class BigFacts(val ok: Boolean, val millis: Long, val oneBigFileRefused: Boolean?, val detail: String)

/**
 * The USB disk Test (docs/HOME_DISK.md §23.10). It never signs in, so it runs anywhere. Pure JVM over a folder,
 * so unit tests run it on a temp folder and the app runs it on the disk's folder. Everything it writes is deleted.
 */
object UsbTest {
    const val TEST_BYTES = 8 * 1024 * 1024
    const val SPLIT_PIECE = 4L * 1024 * 1024
    private const val SPLIT_BYTES = 10 * 1024 * 1024
    private const val CHUNK = 1024 * 1024
    private const val DIR = ".onestream/test"

    fun run(root: File): UsbTestFacts {
        val store = UsbStore(root)
        var facts = UsbTestFacts(freeBytes = store.freeBytes())
        return try {
            store.mkdirs(DIR)
            facts = basic(store, facts)
            facts.copy(splitOk = splitRoundTrip(root))
        } catch (e: Exception) {
            facts.copy(error = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(120))
        } finally {
            runCatching { store.delete(DIR) }
        }
    }

    private fun basic(store: UsbStore, facts: UsbTestFacts): UsbTestFacts {
        val data = Random(7).nextBytes(TEST_BYTES)
        val path = "$DIR/test.bin"
        val t0 = System.nanoTime()
        var pos = 0
        while (pos < data.size) {
            store.write(path, data.size.toLong(), pos.toLong(), data, pos, CHUNK)
            pos += CHUNK
        }
        store.flush(path)
        store.commit(path)
        val writeSec = (System.nanoTime() - t0) / 1e9
        val back = ByteArray(data.size)
        val t1 = System.nanoTime()
        pos = 0
        while (pos < back.size) {
            val n = store.read(path, pos.toLong(), back, pos, CHUNK)
            if (n <= 0) break
            pos += n
        }
        val readSec = (System.nanoTime() - t1) / 1e9
        val hashOk = pos == back.size && MessageDigest.getInstance("SHA-256").digest(back).contentEquals(MessageDigest.getInstance("SHA-256").digest(data))
        // Rename with replace (the piece-map write rule needs it).
        store.write("$DIR/other.bin", 1, 0, byteArrayOf(1))
        store.commit("$DIR/other.bin")
        store.rename(path, "$DIR/other.bin")
        val mb = TEST_BYTES / 1_000_000.0
        return facts.copy(writeMBps = mb / writeSec.coerceAtLeast(1e-3), readMBps = mb / readSec.coerceAtLeast(1e-3), hashOk = hashOk && store.size("$DIR/other.bin") == data.size.toLong())
    }

    /** A 10 MiB file in 4 MiB pieces, written out of order across both boundaries, read back across them. */
    private fun splitRoundTrip(root: File): Boolean {
        val store = UsbStore(root, SPLIT_PIECE)
        val path = "$DIR/split.bin"
        val data = ByteArray(SPLIT_BYTES) { pattern(it.toLong()) }
        for ((from, to) in listOf(6 * CHUNK to 10 * CHUNK, 0 to 5 * CHUNK, 5 * CHUNK to 6 * CHUNK)) {
            store.write(path, data.size.toLong(), from.toLong(), data, from, to - from)
        }
        store.flush(path)
        store.commit(path)
        if (store.size(path) != data.size.toLong() || store.list(DIR).none { it.name == "split.bin" && it.size == data.size.toLong() }) return false
        val physical = listOf("split.bin.001", "split.bin.002", "split.bin.003").all { File(root, "$DIR/$it").isFile }
        if (!physical) return false
        val whole = ByteArray(data.size)
        var got = 0
        while (got < whole.size) {
            val n = store.read(path, got.toLong(), whole, got, minOf(777_777, whole.size - got))
            if (n <= 0) break
            got += n
        }
        if (got != data.size || !whole.contentEquals(data)) return false
        for ((at, len) in listOf(4 * CHUNK - 7 to 20, 8 * CHUNK - 1 to 5, 4 * CHUNK to 1, 4 * CHUNK - 1 to 2, SPLIT_BYTES - 3 to 50)) {
            val buf = ByteArray(len)
            val n = store.read(path, at.toLong(), buf)
            val expect = minOf(len, SPLIT_BYTES - at)
            if (n != expect || !buf.copyOf(n).contentEquals(data.copyOfRange(at, at + n))) return false
        }
        return store.read(path, SPLIT_BYTES.toLong(), ByteArray(4)) == -1
    }

    /**
     * The real boundary: a file of one full [piece] plus 3 MiB, with 5 MiB of pattern written around the first
     * boundary, read back across it. On FAT32 the first piece is real zeros (no sparse files), so this takes about a
     * minute on an SSD: opt-in, never part of the Settings Test. Also tries to make ONE file past 4 GiB, which a
     * FAT32 disk must refuse.
     */
    fun big(root: File, piece: Long = SplitMath.PIECE_SIZE): BigFacts {
        val store = UsbStore(root, piece)
        val started = System.currentTimeMillis()
        val path = "$DIR/big.bin"
        val total = piece + 3L * CHUNK
        return try {
            // A run that was killed halfway (the app stopped, the drive pulled) leaves up to 4 GB behind: start clean.
            runCatching { store.delete(DIR) }
            store.mkdirs(DIR)
            val from = piece - 2L * CHUNK
            val chunk = ByteArray(CHUNK)
            var pos = from
            while (pos < total) {
                val n = minOf(CHUNK.toLong(), total - pos).toInt()
                for (i in 0 until n) chunk[i] = pattern(pos + i)
                store.write(path, total, pos, chunk, 0, n)
                pos += n
            }
            store.flush(path)
            store.commit(path)
            val size = store.size(path)
            var ok = size == total
            val detail = StringBuilder("size=$size/$total")
            for ((at, len) in listOf(piece - 32 * 1024 to 64 * 1024, piece - 1 to 2, total - 10 to 100, from to 4096)) {
                val buf = ByteArray(len)
                val n = store.read(path, at, buf)
                val expect = minOf(len.toLong(), total - at).toInt()
                val right = n == expect && (0 until n).all { buf[it] == pattern(at + it) }
                if (!right) detail.append(" bad@$at(n=$n)")
                ok = ok && right
            }
            BigFacts(ok, System.currentTimeMillis() - started, refusedOneBigFile(root), detail.toString())
        } catch (e: Exception) {
            BigFacts(false, System.currentTimeMillis() - started, null, e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
        } finally {
            runCatching { store.delete(DIR) }
        }
    }

    /** true = the file system refused a single file of 4 GiB + 1 (FAT32); false = it allowed it (exFAT / NTFS). */
    private fun refusedOneBigFile(root: File): Boolean {
        val f = File(root, "$DIR/one-big.bin")
        return try {
            RandomAccessFile(f, "rw").use { it.setLength(DiskTest.FAT32_LIMIT + 2) }
            false
        } catch (e: java.io.IOException) {
            true
        } finally {
            f.delete()
        }
    }

    /** A byte that depends on its position, so a read from the wrong place can't pass. */
    fun pattern(pos: Long): Byte = ((pos * 2654435761L) ushr 13).toByte()

    fun line(f: UsbTestFacts): String = when {
        f.error != null && f.writeMBps == null -> "Couldn't write to the disk: ${f.error}"
        f.error != null -> "Ready · USB · but the split check failed: ${f.error}"
        else -> buildList {
            add("Ready")
            add(VolumeChoice.PROTOCOL)
            f.writeMBps?.let { add("write ${mbps(it)} MB/s") }
            f.readMBps?.let { add("read ${mbps(it)} MB/s") }
            f.freeBytes?.let { add("${it / 1_000_000_000L} GB free") }
            if (f.hashOk == false) add("read-back check FAILED")
            when (f.splitOk) {
                true -> add("files over 4 GB are split")
                false -> add("split check FAILED")
                null -> Unit
            }
        }.joinToString(" · ")
    }

    fun succeeded(f: UsbTestFacts) = f.error == null && f.hashOk == true && f.splitOk == true

    private fun mbps(v: Double) = if (v >= 10) v.toInt().toString() else "%.1f".format(v)
}
