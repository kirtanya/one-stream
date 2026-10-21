package com.kirtanya.reel.player

import com.kirtanya.reel.core.player.NotReadySources
import com.kirtanya.reel.core.sources.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debrid sources that played the addon's "downloading" clip this session (core/player/PlaceholderCheck). Held in
 * memory for 30 minutes: the service is downloading them, so the Sources page tags them "Fetching…" and lists them
 * last, and the player's fallback skips them. [changes] ticks on every change so the Sources page recomposes.
 */
object NotReadyMemory {
    private val sources = NotReadySources()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun now() = android.os.SystemClock.elapsedRealtime()

    fun mark(key: String) {
        sources.mark(key, now())
        _changes.value++
    }

    fun clear(key: String) {
        sources.clear(key)
        _changes.value++
    }

    fun isNotReady(key: String): Boolean = sources.isNotReady(key, now())

    /** The Sources row tag, or null. */
    fun tag(s: Source): String? = NotReadySources.FETCHING_TAG.takeIf { isNotReady(s.key) }
}
