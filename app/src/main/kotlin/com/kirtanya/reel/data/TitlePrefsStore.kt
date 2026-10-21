package com.kirtanya.reel.data

import android.content.Context
import com.kirtanya.reel.core.playback.TitlePrefs

/**
 * Per-title player choices (files/title_prefs.json): audio / subtitle language and track, picture
 * mode, speed. The player reads [get] when it starts and calls [update] when the user changes one.
 */
class TitlePrefsStore(context: Context) {
    private val store = JsonListStore(context, "title_prefs.json", TitlePrefs.serializer())

    fun get(titleKey: String): TitlePrefs? = store.items.value.firstOrNull { it.titleKey == titleKey }

    /** Changes one title's choices (start from `it` and copy what changed). Clearing every field forgets the title. */
    fun update(titleKey: String, change: (TitlePrefs) -> TitlePrefs) = store.update { list ->
        val next = change(get(titleKey) ?: TitlePrefs(titleKey)).copy(titleKey = titleKey, changedAt = System.currentTimeMillis())
        val rest = list.filterNot { it.titleKey == titleKey }
        (if (next.isEmpty) rest else listOf(next) + rest).take(TitlePrefs.MAX_TITLES)
    }

    fun forget(titleKey: String) = store.update { list -> list.filterNot { it.titleKey == titleKey } }
}
