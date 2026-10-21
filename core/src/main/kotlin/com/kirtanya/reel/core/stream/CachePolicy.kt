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

    /**
     * Budget for cached streams given the free space and the cache's current size. [limit] is the user's
     * "Stream cache limit" (null = automatic): it replaces the automatic size but never eats the free-space
     * reserve (docs/STREAMING_V2.md §8.3 C).
     */
    fun budget(formFactor: FormFactor, free: Long, cache: Long, limit: Long? = null): Long {
        val total = free + cache
        val reserve = if (formFactor == FormFactor.TV) (3 * GiB) / 2 else 2 * GiB
        val auto = when (formFactor) {
            FormFactor.PHONE -> (total * 15 / 100).coerceIn(5 * GiB, 20 * GiB)
            FormFactor.TV -> 4 * GiB
        }
        return minOf(limit?.coerceAtLeast(0) ?: auto, total - reserve).coerceAtLeast(0)
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
