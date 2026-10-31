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
 *  - [memory]: per-show learned intro / credits (files/player_memory.json).
 * Per-title tracks, picture, speed and the audio / subtitle delays live in the shared TitlePrefsStore
 * (app.titlePrefs); delays saved here before it had them are moved there once.
 * A process-wide singleton ([get]) so the Settings page and the player share one state.
 */
class PlayerStore private constructor(private val context: Context) {
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
            putInt("volumeBoost", next.volumeBoost)
            putBoolean("dialogueBoost", next.dialogueBoost)
        }
    }

    private fun readOptions() = PlayerOptions(
        countdownS = prefs.getInt("countdownS", PlayerOptions().countdownS),
        seekStepS = prefs.getInt("seekStepS", PlayerOptions().seekStepS),
        volumeBoost = prefs.getInt("volumeBoost", 0),
        dialogueBoost = prefs.getBoolean("dialogueBoost", false),
    ).valid()

    private val titlePrefs get() = com.kirtanya.reel.ReelApp.get(context).titlePrefs

    fun delays(titleKey: String): TitleDelays {
        moveDelaysOnce()
        val p = titlePrefs.get(titleKey) ?: return TitleDelays()
        return TitleDelays(audioDelayMs = p.audioDelayMs ?: 0, subtitleDelayMs = p.subtitleDelayMs ?: 0)
    }

    fun updateDelays(titleKey: String, change: (TitleDelays) -> TitleDelays) {
        val next = change(delays(titleKey))
        titlePrefs.update(titleKey) {
            it.copy(audioDelayMs = next.audioDelayMs.takeIf { ms -> ms != 0 }, subtitleDelayMs = next.subtitleDelayMs.takeIf { ms -> ms != 0 })
        }
    }

    /** Delays saved in player_memory.json before TitlePrefs had fields for them: moved over once. */
    @Synchronized
    private fun moveDelaysOnce() {
        val old = memory.delays.takeIf { it.isNotEmpty() } ?: return
        old.forEach { (key, d) ->
            titlePrefs.update(key) {
                it.copy(
                    audioDelayMs = it.audioDelayMs ?: d.audioDelayMs.takeIf { ms -> ms != 0 },
                    subtitleDelayMs = it.subtitleDelayMs ?: d.subtitleDelayMs.takeIf { ms -> ms != 0 },
                )
            }
        }
        save { it.copy(delays = emptyMap()) }
    }

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
