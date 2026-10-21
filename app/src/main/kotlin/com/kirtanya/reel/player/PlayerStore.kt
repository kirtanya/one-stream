package com.kirtanya.reel.player

import android.content.Context
import androidx.core.content.edit
import com.kirtanya.reel.core.player.PlayerMemory
import com.kirtanya.reel.core.player.PlayerOptions
import com.kirtanya.reel.core.player.ShowSkips
import com.kirtanya.reel.core.player.TitleDelays
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player's own small store, on this device only:
 *  - [options]: app-wide player settings (Settings → Playback countdown and seek step; night mode and
 *    voice boost from the player's panel), SharedPreferences "player_options";
 *  - [memory]: per-title audio / subtitle delays and per-show learned intro / credits
 *    (files/player_memory.json).
 * Per-title tracks, picture and speed live in the shared TitlePrefsStore (app.titlePrefs).
 * A process-wide singleton ([get]) so the Settings page and the player share one state.
 */
class PlayerStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("player_options", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "player_memory.json")
    /** Writes off the main thread, in order. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "player-store").apply { isDaemon = true } }

    private val _options = MutableStateFlow(readOptions())
    val options: StateFlow<PlayerOptions> = _options.asStateFlow()

    @Volatile private var memory: PlayerMemory = PlayerMemory.decode(runCatching { file.readText() }.getOrNull())

    fun updateOptions(change: (PlayerOptions) -> PlayerOptions) {
        val next = change(_options.value).valid()
        _options.value = next
        prefs.edit {
            putInt("countdownS", next.countdownS)
            putInt("seekStepS", next.seekStepS)
            putBoolean("nightMode", next.nightMode)
            putBoolean("voiceBoost", next.voiceBoost)
        }
    }

    private fun readOptions() = PlayerOptions(
        countdownS = prefs.getInt("countdownS", PlayerOptions().countdownS),
        seekStepS = prefs.getInt("seekStepS", PlayerOptions().seekStepS),
        nightMode = prefs.getBoolean("nightMode", false),
        voiceBoost = prefs.getBoolean("voiceBoost", false),
    ).valid()

    fun delays(titleKey: String): TitleDelays = memory.delays(titleKey)

    fun updateDelays(titleKey: String, change: (TitleDelays) -> TitleDelays) = save { it.withDelays(titleKey, System.currentTimeMillis(), change) }

    fun show(titleKey: String): ShowSkips? = memory.shows[titleKey]

    fun updateShow(titleKey: String, change: (ShowSkips) -> ShowSkips) = save { it.withShow(titleKey, System.currentTimeMillis(), change) }

    @Synchronized
    private fun save(change: (PlayerMemory) -> PlayerMemory) {
        val next = change(memory)
        if (next == memory) return
        memory = next
        val text = next.encode()
        writer.execute {
            // Whole file to a temp, then rename: a crash mid-write never leaves half a file.
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        @Volatile private var instance: PlayerStore? = null

        fun get(context: Context): PlayerStore =
            instance ?: synchronized(this) { instance ?: PlayerStore(context.applicationContext).also { instance = it } }
    }
}
