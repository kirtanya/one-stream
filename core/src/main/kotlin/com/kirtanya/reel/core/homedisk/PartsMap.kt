package com.kirtanya.reel.core.homedisk

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Which byte ranges of one data file on the disk are written, flushed and verified
 * (docs/HOME_DISK.md §3.3). Ranges are `[start, end)`, sorted, never overlapping or touching.
 * The file `X.parts` is binary, big-endian, CRC-protected; writers keep the previous one as `X.parts.prev`
 * so a torn write falls back to an older map (which only means re-copying, never trusting unwritten data).
 */
class PartsMap(val fileSize: Long, ranges: List<LongRange> = emptyList(), val generation: Long = 0, val updatedAtMs: Long = 0) {
    init {
        require(fileSize >= 0)
    }

    /** Half-open ranges as `start until end`, kept merged. */
    private val starts = ArrayList<Long>()
    private val ends = ArrayList<Long>()

    init {
        ranges.forEach { add(it.first, it.last + 1) }
    }

    val ranges: List<LongRange> get() = starts.indices.map { starts[it] until ends[it] }
    val rangeCount: Int get() = starts.size
    val coveredBytes: Long get() = starts.indices.sumOf { ends[it] - starts[it] }
    val complete: Boolean get() = fileSize == 0L || (starts.size == 1 && starts[0] == 0L && ends[0] == fileSize)

    /** Marks `[start, end)` as on the disk; merges with neighbours it overlaps or touches. */
    fun add(start: Long, end: Long) {
        require(start in 0..end && end <= fileSize) { "range outside the file" }
        if (start == end) return
        var s = start
        var e = end
        // First range that could touch [s, e): the one whose end ≥ s.
        var i = firstEndAtLeast(s)
        while (i < starts.size && starts[i] <= e) {
            s = minOf(s, starts[i])
            e = maxOf(e, ends[i])
            starts.removeAt(i)
            ends.removeAt(i)
        }
        starts.add(i, s)
        ends.add(i, e)
    }

    /** True when every byte of `[start, end)` is on the disk (empty ranges are). */
    fun contains(start: Long, end: Long): Boolean {
        if (start >= end) return true
        val i = firstEndAtLeast(start + 1)
        return i < starts.size && starts[i] <= start && ends[i] >= end
    }

    /** The first byte at or after [from] that is not on the disk, or null when the rest is complete. */
    fun firstGap(from: Long = 0): Long? {
        if (from >= fileSize) return null
        val i = firstEndAtLeast(from + 1)
        return when {
            i >= starts.size -> from
            starts[i] > from -> from
            ends[i] >= fileSize -> null
            else -> ends[i]
        }
    }

    /** The next generation with the same ranges (writers bump it on every rewrite). */
    fun next(nowMs: Long): PartsMap = PartsMap(fileSize, ranges, generation + 1, nowMs)

    fun encode(): ByteArray {
        val n = starts.size
        val buf = ByteBuffer.allocate(HEADER + 16 * n + 4)
        buf.put(MAGIC)
        buf.putShort(VERSION.toShort())
        buf.putShort(if (complete) FLAG_COMPLETE.toShort() else 0)
        buf.putLong(fileSize)
        buf.putLong(generation)
        buf.putLong(updatedAtMs)
        buf.putInt(n)
        for (k in 0 until n) {
            buf.putLong(starts[k])
            buf.putLong(ends[k])
        }
        val crc = CRC32().apply { update(buf.array(), 0, HEADER + 16 * n) }
        buf.putInt(crc.value.toInt())
        return buf.array()
    }

    private fun firstEndAtLeast(pos: Long): Int {
        var lo = 0
        var hi = ends.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] < pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    override fun toString() = "PartsMap(size=$fileSize, gen=$generation, ranges=$ranges)"

    companion object {
        private val MAGIC = byteArrayOf(0x4F, 0x53, 0x50, 0x4D) // "OSPM"
        const val VERSION = 1
        const val FLAG_COMPLETE = 1
        private const val HEADER = 36

        /** Null when [bytes] is short, of another format or version, CRC-damaged, or its ranges are invalid. */
        fun decode(bytes: ByteArray?): PartsMap? {
            if (bytes == null || bytes.size < HEADER + 4) return null
            val buf = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4).also(buf::get)
            if (!magic.contentEquals(MAGIC)) return null
            if (buf.short.toInt() != VERSION) return null
            buf.short // flags: derived from the ranges
            val size = buf.long
            val gen = buf.long
            val updated = buf.long
            val n = buf.int
            if (n < 0 || size < 0 || bytes.size != HEADER + 16 * n + 4) return null
            val crc = CRC32().apply { update(bytes, 0, HEADER + 16 * n) }
            if (ByteBuffer.wrap(bytes, HEADER + 16 * n, 4).int != crc.value.toInt()) return null
            val list = ArrayList<LongRange>(n)
            var prevEnd = -1L
            repeat(n) {
                val s = buf.long
                val e = buf.long
                // Sorted, non-overlapping, non-touching, inside the file: anything else is not a map we wrote.
                if (s <= prevEnd || s >= e || e > size) return null
                list += s until e
                prevEnd = e
            }
            return PartsMap(size, list, gen, updated)
        }

        /** The read rule (§3.3): `X.parts`, else `X.parts.prev`, else an empty map. */
        fun readBest(current: ByteArray?, previous: ByteArray?, fileSize: Long): PartsMap =
            decode(current)?.takeIf { it.fileSize == fileSize }
                ?: decode(previous)?.takeIf { it.fileSize == fileSize }
                ?: PartsMap(fileSize)
    }
}
