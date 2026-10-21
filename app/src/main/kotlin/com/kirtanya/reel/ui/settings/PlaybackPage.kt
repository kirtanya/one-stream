package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.player.AutoNext
import com.kirtanya.reel.core.player.SeekStep
import com.kirtanya.reel.player.PlayerStore
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Settings → Playback: auto-play the next episode and its countdown, and how far one skip goes (the
 * remote's ←/→, the ± buttons, a double-tap). Night mode, voice boost, delays and the sleep timer are
 * in the player's own panel, where their effect can be heard.
 */
internal fun LazyListScope.playbackItems(@Suppress("UNUSED_PARAMETER") isTv: Boolean) {
    item(key = "playback-autoplay") { AutoplayRow() }
    item(key = "playback-countdown") {
        val options by PlayerStore.get(LocalContext.current).options.collectAsState()
        val store = PlayerStore.get(LocalContext.current)
        Section("Countdown before the next episode", note = "After 3 episodes in a row without touching anything, the player asks if you're still watching.") {
            Choices(AutoNext.COUNTDOWNS, options.countdownS, { "$it s" }) { s -> store.updateOptions { it.copy(countdownS = s) } }
        }
    }
    item(key = "playback-seek") {
        val options by PlayerStore.get(LocalContext.current).options.collectAsState()
        val store = PlayerStore.get(LocalContext.current)
        Section("Skip by", note = "The remote's ← / →, the skip buttons and a double-tap on the phone.") {
            Choices(SeekStep.STEPS, options.seekStepS, { "$it s" }) { s -> store.updateOptions { it.copy(seekStepS = s) } }
        }
    }
}

@Composable
private fun AutoplayRow() {
    val app = ReelApp.get(LocalContext.current)
    val playback by app.playbackSettings.state.collectAsState()
    val options by PlayerStore.get(LocalContext.current).options.collectAsState()
    Section {
        ToggleRow(
            "Play the next episode automatically",
            "After a ${options.countdownS}-second countdown.",
            playback.autoplayNext,
        ) { app.playbackSettings.update { it.copy(autoplayNext = !it.autoplayNext) } }
    }
}

/** One button per value; the picked one is filled. */
@Composable
private fun <T> Choices(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        values.forEach { v -> ReelButton(label(v), { onPick(v) }, kind = if (v == selected) ButtonKind.Primary else ButtonKind.Ghost) }
    }
}
