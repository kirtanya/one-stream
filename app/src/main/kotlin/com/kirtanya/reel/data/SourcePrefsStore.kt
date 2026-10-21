package com.kirtanya.reel.data

import android.content.Context
import androidx.core.content.edit
import com.kirtanya.reel.core.sources.RankPrefs
import com.kirtanya.reel.core.sources.SourcePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Source preferences for this device (prefs "source_prefs"); every source search ranks with them. */
class SourcePrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("source_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SourcePrefs> = _state.asStateFlow()

    fun update(change: (SourcePrefs) -> SourcePrefs) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit {
            putInt("maxResolution", next.maxResolution ?: 0)
            putBoolean("neverCam", next.neverCam)
            putStringSet("pinned", next.pinned)
            putStringSet("blocked", next.blocked)
        }
    }

    fun applyTo(base: RankPrefs): RankPrefs = _state.value.applyTo(base)

    private fun read() = SourcePrefs(
        maxResolution = prefs.getInt("maxResolution", 0).takeIf { it > 0 },
        neverCam = prefs.getBoolean("neverCam", false),
        pinned = prefs.getStringSet("pinned", null).orEmpty().toSet(),
        blocked = prefs.getStringSet("blocked", null).orEmpty().toSet(),
    )
}
