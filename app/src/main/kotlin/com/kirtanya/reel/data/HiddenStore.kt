package com.kirtanya.reel.data

import android.content.Context
import com.kirtanya.reel.core.library.Hidden
import com.kirtanya.reel.core.library.HiddenTitle
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TitleSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** "Not interested": titles to leave out of Home rows, search and recommendations (files/hidden.json). Synced phone → TV. */
class HiddenStore(context: Context) {
    private val store = JsonListStore(context, "hidden.json", HiddenTitle.serializer())

    val all: StateFlow<List<HiddenTitle>> get() = store.items

    /** Keys ("movie:550") currently hidden: filter lists with `it.ref.toString() !in keys`. */
    val keys: Flow<Set<String>> = store.items.map(Hidden::keys).distinctUntilChanged()

    /** Hidden titles, newest first (for a "Hidden titles" list in Settings, to bring one back). */
    val titles: Flow<List<HiddenTitle>> = store.items.map { l -> l.filter { it.hidden } }.distinctUntilChanged()

    fun isHidden(ref: TitleRef) = store.items.value.any { it.key == ref.toString() && it.hidden }

    fun hide(t: TitleSummary) = set(HiddenTitle(t.ref.toString(), t.title, t.posterPath, hidden = true, changedAt = now()))

    fun unhide(ref: TitleRef) {
        val have = store.items.value.firstOrNull { it.key == ref.toString() } ?: return
        set(have.copy(hidden = false, changedAt = now()))
    }

    /** Leaves out hidden titles. */
    fun <T> filter(list: List<T>, ref: (T) -> TitleRef): List<T> {
        val k = Hidden.keys(store.items.value)
        return if (k.isEmpty()) list else list.filter { ref(it).toString() !in k }
    }

    fun mergeFrom(incoming: List<HiddenTitle>): Int {
        val before = Hidden.keys(store.items.value)
        val after = Hidden.keys(store.update { Hidden.merge(it, incoming, now()) })
        return (before - after).size + (after - before).size
    }

    private fun set(item: HiddenTitle) = store.update { Hidden.merge(it, listOf(item), now()) }

    private fun now() = System.currentTimeMillis()
}
