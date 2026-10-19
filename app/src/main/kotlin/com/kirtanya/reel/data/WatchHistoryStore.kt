package com.kirtanya.reel.data

import android.content.Context
import android.util.Log
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Playback positions, saved in files/history.json (newest first, capped). */
class WatchHistoryStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "history.json")
    private val lock = Any()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()

    fun get(key: String): WatchEntry? = _entries.value.firstOrNull { it.playback.key == key }

    fun continueWatching(now: Long = System.currentTimeMillis()): List<ContinueItem> = ContinueWatching.select(_entries.value, now)

    /** Records [positionMs] of [durationMs] for [playback]. Ignores the first seconds (opened by mistake). */
    fun save(playback: Playback, positionMs: Long, durationMs: Long) {
        if (positionMs < 10_000 && get(playback.key) == null) return
        val entry = WatchEntry(playback, positionMs, durationMs, System.currentTimeMillis())
        update { list -> (listOf(entry) + list.filterNot { it.playback.key == playback.key }).take(MAX_ENTRIES) }
    }

    /** Removes everything for a film or show from Continue watching. */
    fun removeTitle(titleKey: String) = update { list -> list.filterNot { it.playback.titleKey == titleKey } }

    private fun update(change: (List<WatchEntry>) -> List<WatchEntry>) {
        synchronized(lock) {
            val next = change(_entries.value)
            _entries.value = next
            runCatching {
                val tmp = File(file.parentFile, "history.json.tmp")
                tmp.writeText(json.encodeToString(ListSerializer(WatchEntry.serializer()), next))
                tmp.renameTo(file)
            }.onFailure { Log.w("ReelHistory", "save failed", it) }
        }
    }

    private fun load(): List<WatchEntry> =
        runCatching { json.decodeFromString(ListSerializer(WatchEntry.serializer()), file.readText()) }.getOrDefault(emptyList())

    companion object {
        private const val MAX_ENTRIES = 300
    }
}
