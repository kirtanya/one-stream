package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.core.stream.NightRule
import com.kirtanya.reel.core.stream.WatchCap
import com.kirtanya.reel.engine.DownloadRulesStore
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Settings → Storage: when downloads may run (overnight only) and how much they may take while you watch
 * (docs/STREAMING_V2.md §8.3). Plays are never held or slowed by these.
 */
internal fun LazyListScope.downloadRulesItems(@Suppress("UNUSED_PARAMETER") isTv: Boolean) {
    item(key = "rules-night") { NightSection() }
    item(key = "rules-watch") { WatchCapSection() }
}

@Composable
private fun NightSection() {
    val store = DownloadRulesStore.get(LocalContext.current)
    val rules by store.rules.collectAsState()
    val night = rules.night
    Section("Download schedule", note = "Playing is never held. \"Start now\" on a download skips the wait.") {
        ToggleRow(
            "Download only overnight",
            "Downloads wait until ${NightRule.hourLabel(night.startHour)} and pause at ${NightRule.hourLabel(night.endHour)}.",
            night.enabled,
        ) { store.update { it.copy(night = it.night.copy(enabled = !it.night.enabled)) } }
        if (night.enabled) {
            HourRow("Starts at", night.startHour, "start") { h -> store.update { it.copy(night = it.night.copy(startHour = h)) } }
            HourRow("Stops at", night.endHour, "stop") { h -> store.update { it.copy(night = it.night.copy(endHour = h)) } }
        }
    }
}

/** An hour with − / + (a remote steps through 24 values faster than it scrolls a list of them). */
@Composable
private fun HourRow(title: String, hour: Int, what: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Tokens.Type.label, color = Tokens.Colors.text)
            Text(NightRule.hourLabel(hour), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
        }
        GlassIconButton(Icons.Rounded.Remove, "Earlier $what", { onChange((hour + 23) % 24) })
        GlassIconButton(Icons.Rounded.Add, "Later $what", { onChange((hour + 1) % 24) })
    }
}

@Composable
private fun WatchCapSection() {
    val store = DownloadRulesStore.get(LocalContext.current)
    val rules by store.rules.collectAsState()
    Section("While you watch", note = "Other downloads slow down so what you watch gets the bandwidth. They speed up a few seconds after you pause or stop.") {
        Text("Limit other downloads to", style = Tokens.Type.label, color = Tokens.Colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            WatchCap.entries.forEach { c ->
                ReelButton(capLabel(c), { store.update { it.copy(watchCap = c) } }, kind = if (rules.watchCap == c) ButtonKind.Primary else ButtonKind.Ghost)
            }
        }
    }
}

private fun capLabel(c: WatchCap): String = when (c) {
    WatchCap.OFF -> "No limit"
    WatchCap.K500 -> "500 KB/s"
    WatchCap.M1 -> "1 MB/s"
    WatchCap.M2 -> "2 MB/s"
    WatchCap.AUTO -> "Auto (25 %)"
}
