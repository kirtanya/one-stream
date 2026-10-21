package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.library.HiddenTitle
import com.kirtanya.reel.core.library.Watchlist
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.sources.SourcePrefs
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.theme.Tokens

// ─── History ───────────────────────────────────────────────────────────────────────────────

internal fun historySummary(app: ReelApp): String {
    val n = app.history.entries.value.size
    return if (n == 0) "Nothing watched yet" else "${com.kirtanya.reel.ui.plural(n, "film or episode", "films and episodes")} · Not interested"
}

/** Settings → History: what you watched by day (delete a row or everything), and titles you hid. */
internal fun LazyListScope.historyItems() {
    item(key = "history") { HistoryList() }
    item(key = "hidden") { HiddenList() }
}

@Composable
private fun HistoryList() {
    val app = ReelApp.get(LocalContext.current)
    val entries by app.history.entries.collectAsState()
    val days = remember(entries) { app.history.byDay() }
    var confirmClear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        if (days.isEmpty()) {
            Section("Watched") { Text("Films and episodes you play show up here.", style = Tokens.Type.body, color = Tokens.Colors.textTertiary) }
        }
        days.take(30).forEach { day ->
            Section(day.label) { day.entries.forEach { e -> HistoryRow(e) { app.history.delete(e.playback.key) } } }
        }
        if (days.isNotEmpty()) ReelButton("Clear history", { confirmClear = true }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Clear your watch history?", style = Tokens.Type.heading, color = Tokens.Colors.text) },
            text = { Text("Continue watching empties too. Watched ticks stay.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
            confirmButton = { ReelButton("Clear", { app.history.clear(); confirmClear = false }, kind = ButtonKind.Primary, icon = Icons.Rounded.Delete) },
            dismissButton = { ReelButton("Keep it", { confirmClear = false }, kind = ButtonKind.Ghost) },
        )
    }
}

@Composable
private fun HistoryRow(e: WatchEntry, onDelete: () -> Unit) {
    val p = e.playback
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        RemoteImage(TmdbImages.poster(p.posterPath, "w185"), Modifier.width(40.dp).size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(Tokens.Radius.s)))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(p.subtitle ?: p.episode?.label, if (e.finished) "Watched" else "${(e.progress * 100).toInt()}% watched").joinToString(" · ")
            Text(detail, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassIconButton(Icons.Rounded.Close, "Remove ${p.title} from history", onDelete)
    }
}

@Composable
private fun HiddenList() {
    val app = ReelApp.get(LocalContext.current)
    val hidden by app.hidden.titles.collectAsState(initial = emptyList())
    Section("Not interested", note = "Hidden titles don't show on Home, in Search or in grids.") {
        if (hidden.isEmpty()) Text("Nothing hidden. Long-press a poster and choose Not interested.", style = Tokens.Type.body, color = Tokens.Colors.textTertiary)
        hidden.forEach { h -> HiddenRow(h) { Watchlist.parseRef(h.key)?.let(app.hidden::unhide) } }
    }
}

@Composable
private fun HiddenRow(h: HiddenTitle, onShow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        RemoteImage(TmdbImages.poster(h.posterPath, "w185"), Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(Tokens.Radius.s)))
        Text(h.title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        ReelButton("Show", onShow, kind = ButtonKind.Ghost, icon = Icons.Rounded.Visibility)
    }
}

// ─── Source preferences ────────────────────────────────────────────────────────────────────

internal fun sourcePrefsSummary(p: SourcePrefs): String = listOfNotNull(
    SourcePrefs.CAPS.firstOrNull { it.first == p.maxResolution }?.second?.let { if (p.maxResolution == null) "Any quality" else "Up to $it" },
    if (p.neverCam) "never CAM" else null,
    p.pinned.size.takeIf { it > 0 }?.let { "$it preferred" },
    p.blocked.size.takeIf { it > 0 }?.let { "$it blocked" },
).joinToString(" · ")

/** Settings → Source preferences (this device only): quality cap, never CAM, preferred / blocked providers. */
internal fun LazyListScope.sourcePrefsItems(isTv: Boolean) {
    item(key = "quality") {
        val app = ReelApp.get(LocalContext.current)
        val prefs by app.sourcePrefs.state.collectAsState()
        Section("Quality", note = if (isTv) "Bigger files than the TV can hold are left out anyway." else "Sharper sources still show, just lower in the list.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                SourcePrefs.CAPS.forEach { (res, label) ->
                    ReelButton(label, { app.sourcePrefs.update { it.copy(maxResolution = res) } }, kind = if (prefs.maxResolution == res) ButtonKind.Primary else ButtonKind.Ghost)
                }
            }
            ToggleRow("Never show CAM copies", "Leaves out recordings made in a cinema.", prefs.neverCam) {
                app.sourcePrefs.update { it.copy(neverCam = !it.neverCam) }
            }
        }
    }
    item(key = "providers") {
        val app = ReelApp.get(LocalContext.current)
        val prefs by app.sourcePrefs.state.collectAsState()
        val installed = installedSources(app)
        val names = remember(installed) {
            (installed.addons.mapNotNull { runCatching { it.manifest.name }.getOrNull() } + installed.plugins.map { it.name }).distinct()
        }
        Section("Addons and plugins", note = "Preferred ones come first among equally good sources; blocked ones are never asked.") {
            if (names.isEmpty()) Text("Add addons or plugins first.", style = Tokens.Type.body, color = Tokens.Colors.textTertiary)
            names.forEach { n ->
                val pinned = n in prefs.pinned
                val blocked = n in prefs.blocked
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Column(Modifier.weight(1f)) {
                        Text(n, style = Tokens.Type.label, color = if (blocked) Tokens.Colors.textTertiary else Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (pinned || blocked) Text(if (pinned) "Preferred" else "Blocked", style = Tokens.Type.caption, color = if (pinned) Tokens.Colors.accentBright else Tokens.Colors.warn)
                    }
                    GlassIconButton(Icons.Rounded.PushPin, if (pinned) "Stop preferring $n" else "Prefer $n", { app.sourcePrefs.update { it.pin(n, !pinned) } })
                    GlassIconButton(Icons.Rounded.Block, if (blocked) "Unblock $n" else "Block $n", { app.sourcePrefs.update { it.block(n, !blocked) } })
                }
            }
        }
    }
}

// ─── Auto-download (Storage page) ──────────────────────────────────────────────────────────

/** Settings → Storage: next episodes downloaded by themselves, watched ones cleaned up, the stream-cache size. */
internal fun LazyListScope.autoDownloadItems(isTv: Boolean) {
    item(key = "auto") {
        val app = ReelApp.get(LocalContext.current)
        val s by app.autoDownloader.settings.collectAsState()
        val last by app.autoDownloader.lastRun.collectAsState()
        Section(
            "Auto-download",
            note = if (isTv) "Downloads the next episodes of shows on My list that you're watching." else "Only at home on Wi-Fi, never on mobile data or the office network.",
        ) {
            ToggleRow("Download next episodes", "For shows on My list you're watching.", s.enabled) { app.autoDownloader.update { it.copy(enabled = !it.enabled) } }
            if (s.enabled) {
                Text("Keep ready", style = Tokens.Type.label, color = Tokens.Colors.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    com.kirtanya.reel.core.library.AutoDownloadSettings.KEEP_CHOICES.forEach { n ->
                        ReelButton(com.kirtanya.reel.ui.plural(n, "episode"), { app.autoDownloader.update { it.copy(keepNext = n) } }, kind = if (s.keepNext == n) ButtonKind.Primary else ButtonKind.Ghost)
                    }
                }
                last?.let { Text("Last check: $it", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    item(key = "cleanup") {
        val app = ReelApp.get(LocalContext.current)
        val s by app.autoDownloader.settings.collectAsState()
        Section("Clean up") {
            Text("Delete downloads you've watched", style = Tokens.Type.label, color = Tokens.Colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                com.kirtanya.reel.core.library.AutoDownloadSettings.DELETE_CHOICES.forEach { d ->
                    val label = when (d) { null -> "Never"; 1 -> "After a day"; else -> "After $d days" }
                    ReelButton(label, { app.autoDownloader.update { it.copy(deleteWatchedAfterDays = d) } }, kind = if (s.deleteWatchedAfterDays == d) ButtonKind.Primary else ButtonKind.Ghost)
                }
            }
            Text("Stream cache limit", style = Tokens.Type.label, color = Tokens.Colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                com.kirtanya.reel.core.library.AutoDownloadSettings.CACHE_CHOICES.forEach { g ->
                    ReelButton(if (g == null) "Automatic" else "$g GB", { app.autoDownloader.update { it.copy(cacheLimitGb = g) } }, kind = if (s.cacheLimitGb == g) ButtonKind.Primary else ButtonKind.Ghost)
                }
            }
        }
    }
}
