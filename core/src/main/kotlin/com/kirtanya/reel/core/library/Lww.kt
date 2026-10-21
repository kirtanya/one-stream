package com.kirtanya.reel.core.library

/**
 * Last-write-wins merge for small synced lists (watched marks, hidden titles): per key the newest
 * change wins, ties go to the incoming copy, removals stay as tombstones for [tombstoneMs] so they
 * can travel to the TV and beat an older "add" there.
 */
object Lww {
    const val TOMBSTONE_MS = 90L * 24 * 60 * 60 * 1000

    fun <T> merge(
        local: List<T>,
        incoming: List<T>,
        now: Long,
        key: (T) -> String,
        changedAt: (T) -> Long,
        isTombstone: (T) -> Boolean,
    ): List<T> {
        val byKey = LinkedHashMap<String, T>()
        (local + incoming).forEach { item ->
            val have = byKey[key(item)]
            if (have == null || changedAt(item) >= changedAt(have)) byKey[key(item)] = item
        }
        return byKey.values
            .filterNot { isTombstone(it) && now - changedAt(it) > TOMBSTONE_MS }
            .sortedByDescending(changedAt)
    }
}
