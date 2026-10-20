package com.kirtanya.reel.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.kirtanya.reel.core.library.Watchlist
import com.kirtanya.reel.core.library.WatchlistItem
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * "My list": titles saved for later, in files/watchlist.json. The phone sends it to paired TVs
 * with the sources; the TV merges it (newest change per title wins).
 */
class WatchlistStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.filesDir, "watchlist.json"))
    private val lock = Any()

    /** Everything, removals included (what sync sends). */
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<WatchlistItem>> = _all.asStateFlow()

    /** The list to show, newest first. */
    val items: Flow<List<WatchlistItem>> = _all.map(Watchlist::visible).distinctUntilChanged()

    fun contains(ref: TitleRef): Boolean = _all.value.any { it.key == ref.toString() && !it.removed }

    /** For a "saved" toggle on a title page or poster. */
    fun isSaved(ref: TitleRef): Flow<Boolean> = _all.map { list -> list.any { it.key == ref.toString() && !it.removed } }.distinctUntilChanged()

    fun add(title: TitleSummary) = update { list -> Watchlist.merge(list, listOf(WatchlistItem.of(title, now())), now()) }

    fun remove(ref: TitleRef) = update { list ->
        val have = list.firstOrNull { it.key == ref.toString() } ?: return@update list
        Watchlist.merge(list, listOf(have.copy(removed = true, changedAt = now())), now())
    }

    /** Adds or removes; returns true if the title is now saved. */
    fun toggle(title: TitleSummary): Boolean {
        val saved = contains(title.ref)
        if (saved) remove(title.ref) else add(title)
        return !saved
    }

    /** Merges a list sent by the phone. Returns how many titles changed on this device. */
    fun mergeFrom(incoming: List<WatchlistItem>): Int {
        var changed = 0
        update { list ->
            val merged = Watchlist.merge(list, incoming, now())
            changed = Watchlist.visible(merged).map { it.key }.toSet().let { after ->
                val before = Watchlist.visible(list).map { it.key }.toSet()
                (after - before).size + (before - after).size
            }
            merged
        }
        return changed
    }

    private fun now() = System.currentTimeMillis()

    private val pending = Channel<List<WatchlistItem>>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { for (snapshot in pending) write(snapshot) }
    }

    private fun update(change: (List<WatchlistItem>) -> List<WatchlistItem>) {
        val next = synchronized(lock) { change(_all.value).also { _all.value = it } }
        pending.trySend(next)
    }

    private fun write(snapshot: List<WatchlistItem>) {
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = file.startWrite()
            out!!.write(json.encodeToString(ListSerializer(WatchlistItem.serializer()), snapshot).toByteArray())
            file.finishWrite(out)
        }.onFailure {
            out?.let(file::failWrite)
            Log.w(TAG, "save failed", it)
        }
    }

    private fun load(): List<WatchlistItem> {
        if (!file.baseFile.exists()) return emptyList()
        val text = runCatching { file.readFully().decodeToString() }.getOrNull() ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
        if (array == null) {
            Log.w(TAG, "watchlist unreadable; kept as watchlist.json.bad")
            runCatching { file.baseFile.copyTo(File(file.baseFile.parentFile, "watchlist.json.bad"), overwrite = true) }
            return emptyList()
        }
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(WatchlistItem.serializer(), it) }.getOrNull() }
    }

    companion object {
        private const val TAG = "ReelWatchlist"
    }
}
