package com.kirtanya.reel.core.stream

/** One cached stream torrent, as the cache policy sees it. */
data class CacheEntry(
    val key: String,
    val bytes: Long,
    val lastUsedAt: Long,
    val lastStoppedAt: Long,
    /** In use or being started: never evicted. */
    val claimed: Boolean,
)

/**
 * How big the stream cache may grow, and what to evict (docs/STREAMING_V2.md §7.4).
 *
 * The old fixed 2 GB budget deleted the title you had just stopped as soon as it passed 2 GB
 * (~12 minutes of streaming), so "resume" started from zero.
 */
object CachePolicy {
    fun hardFloor(formFactor: FormFactor): Long = if (formFactor == FormFactor.TV) 1 * GiB else 2 * GiB

    /** Budget for cached streams given the free space and the cache's current size. */
    fun budget(formFactor: FormFactor, free: Long, cache: Long): Long {
        val total = free + cache
        return when (formFactor) {
            FormFactor.PHONE -> minOf((total * 15 / 100).coerceIn(5 * GiB, 20 * GiB), total - 2 * GiB)
            FormFactor.TV -> minOf(4 * GiB, total - (3 * GiB) / 2)
        }.coerceAtLeast(0)
    }

    /**
     * Keys to evict, oldest first, until the cache fits [budget] and free space is above the floor.
     * The two most recently stopped titles are protected unless space is critically low;
     * [explicit] (the user's "Clear") ignores protection. Claimed entries are never evicted.
     */
    fun evict(entries: List<CacheEntry>, budget: Long, free: Long, hardFloor: Long, explicit: Boolean): List<String> {
        val candidates = entries.filter { !it.claimed }
        if (explicit) return candidates.map { it.key }
        val protected = candidates.filter { it.lastStoppedAt > 0 }.sortedByDescending { it.lastStoppedAt }.take(2).map { it.key }.toSet()
        var used = entries.sumOf { it.bytes }
        var freeNow = free
        val out = ArrayList<String>()
        fun done() = used <= budget && freeNow >= hardFloor
        val lru = compareBy<CacheEntry> { maxOf(it.lastUsedAt, it.lastStoppedAt) }
        for (e in candidates.filter { it.key !in protected }.sortedWith(lru)) {
            if (done()) break
            out += e.key; used -= e.bytes; freeNow += e.bytes
        }
        // Protected titles go only when the disk is critically full.
        for (e in candidates.filter { it.key in protected }.sortedWith(lru)) {
            if (freeNow >= hardFloor) break
            out += e.key; used -= e.bytes; freeNow += e.bytes
        }
        return out
    }
}
