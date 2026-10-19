package com.kirtanya.reel.data

import android.content.Context
import android.util.Log
import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.ContinueWatching
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.playback.WatchEntry
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Playback positions, saved in files/history.json (newest first, capped). */
class WatchHistoryStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.filesDir, "history.json"))
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
            // AtomicFile: write to a side file, fsync, then swap — a crash never leaves half a file.
            var out: java.io.FileOutputStream? = null
            runCatching {
                out = file.startWrite()
                out!!.write(json.encodeToString(ListSerializer(WatchEntry.serializer()), next).toByteArray())
                file.finishWrite(out)
            }.onFailure {
                out?.let(file::failWrite)
                Log.w(TAG, "save failed", it)
            }
        }
    }

    /** Reads entries one by one, so one bad entry doesn't cost the rest; an unreadable file is kept aside, not overwritten. */
    private fun load(): List<WatchEntry> {
        if (!file.baseFile.exists()) return emptyList()
        val text = runCatching { file.readFully().decodeToString() }.getOrNull() ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
        if (array == null) {
            Log.w(TAG, "history unreadable; kept as history.json.bad")
            runCatching { file.baseFile.copyTo(File(file.baseFile.parentFile, "history.json.bad"), overwrite = true) }
            return emptyList()
        }
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(WatchEntry.serializer(), it) }.getOrNull() }
    }

    companion object {
        private const val TAG = "ReelHistory"
        private const val MAX_ENTRIES = 300
    }
}
