package com.kirtanya.reel.engine

import android.content.Context
import com.kirtanya.reel.core.stream.DownloadRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The engine's download rules (docs/STREAMING_V2.md §8.3): overnight window and the speed cap while watching.
 * Per device. Its own store, so Settings can show it without creating or starting the engine.
 */
class DownloadRulesStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("download_rules", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _rules = MutableStateFlow(
        prefs.getString("rules", null)?.let { runCatching { json.decodeFromString(DownloadRules.serializer(), it) }.getOrNull() } ?: DownloadRules(),
    )
    val rules: StateFlow<DownloadRules> = _rules.asStateFlow()

    fun update(change: (DownloadRules) -> DownloadRules) {
        val next = change(_rules.value)
        if (next == _rules.value) return
        _rules.value = next
        prefs.edit().putString("rules", json.encodeToString(DownloadRules.serializer(), next)).apply()
    }

    companion object {
        @Volatile private var instance: DownloadRulesStore? = null

        fun get(context: Context): DownloadRulesStore =
            instance ?: synchronized(this) { instance ?: DownloadRulesStore(context.applicationContext).also { instance = it } }
    }
}
