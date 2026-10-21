package com.kirtanya.reel.core.homedisk

/** A title as eviction sees it (from the TV's library index). */
data class StoredTitle(
    val key: String,
    val kind: TitleKind,
    val bytes: Long,
    val lastUsedAt: Long,
    val completedAt: Long? = null,
    /** A live lease, playing, being conveyed, or marked Keep: never evicted. */
    val pinned: Boolean = false,
)

enum class EvictReason { AGE, BUDGET, FLOOR }

data class Eviction(val key: String, val reason: EvictReason, val bytes: Long)

/**
 * Which stream-cache titles the TV evicts (docs/HOME_DISK.md §13). Only `cache/` is ever evicted; `library/`
 * never. Expired titles go first, then the oldest while the cache is over its share of the disk or the disk
 * is below its free-space floor. Runs on the TV only (from Phase 8); the rules live here so they're tested.
 */
object RetentionPolicy {
    const val DAY_MS = 86_400_000L
    const val FLOOR_MIN = 20_000_000_000L

    fun floor(totalBytes: Long): Long = maxOf(FLOOR_MIN, totalBytes / 20)
    fun cacheBudget(totalBytes: Long, settings: RetentionSettings): Long = totalBytes / 100 * settings.cacheSharePct.coerceIn(0, 100)

    fun evict(titles: List<StoredTitle>, now: Long, totalBytes: Long, freeBytes: Long, settings: RetentionSettings): List<Eviction> {
        val cache = titles.filter { it.kind == TitleKind.CACHE && !it.pinned }.sortedBy { lastTouch(it) }
        val out = ArrayList<Eviction>()
        var cacheBytes = titles.filter { it.kind == TitleKind.CACHE }.sumOf { it.bytes }
        var free = freeBytes
        if (settings.days > 0) {
            cache.filter { now - lastTouch(it) > settings.days * DAY_MS }.forEach {
                out += Eviction(it.key, EvictReason.AGE, it.bytes)
                cacheBytes -= it.bytes
                free += it.bytes
            }
        }
        val budget = cacheBudget(totalBytes, settings)
        val floor = floor(totalBytes)
        for (t in cache) {
            if (out.any { it.key == t.key }) continue
            val reason = when {
                free < floor -> EvictReason.FLOOR
                cacheBytes > budget -> EvictReason.BUDGET
                else -> break
            }
            out += Eviction(t.key, reason, t.bytes)
            cacheBytes -= t.bytes
            free += t.bytes
        }
        return out
    }

    private fun lastTouch(t: StoredTitle) = maxOf(t.lastUsedAt, t.completedAt ?: 0)
}
