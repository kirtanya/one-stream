package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.data.PlaybackSettings
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.data.SubtitleSize
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.ReelCard
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"

/** Grouped cards: Playback, Subtitles, Addons, Plugins, Storage, About. Same screen on phone and TV. */
@Composable
fun SettingsScreen(isTv: Boolean) {
    val app = ReelApp.get(LocalContext.current)
    val state by app.sources.state.collectAsState()
    val playback by app.playbackSettings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var addonUrl by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { app.engine.cacheBytes() } }

    fun run(action: suspend () -> String) {
        busy = true
        scope.launch {
            message = try {
                action()
            } catch (e: SourceSetupException) {
                e.message
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            busy = false
        }
    }

    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val cardWidth = Modifier.fillMaxWidth().widthIn(max = 860.dp)
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(Tokens.Colors.accent, strength = 0.25f)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.l else 0.dp, bottom = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.bottomBarClearance + Tokens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            item {
                Column(Modifier.then(if (isTv) Modifier else Modifier.statusBarsPadding()).padding(top = Tokens.Space.l)) {
                    Text("Settings", style = Tokens.Type.display, color = Tokens.Colors.text)
                    message?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
                }
            }

            item {
                Group(Icons.Rounded.PlayCircle, "Playback", cardWidth) {
                    ToggleRow(
                        "Play the next episode automatically",
                        "Finds a source near the end of an episode and starts it after a 10-second countdown.",
                        playback.autoplayNext,
                    ) { app.playbackSettings.update { it.copy(autoplayNext = !it.autoplayNext) } }
                }
            }

            item {
                Group(Icons.Rounded.Subtitles, "Subtitles", cardWidth) {
                    Text("Language", style = Tokens.Type.label, color = Tokens.Colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        (listOf<Pair<String?, String>>(null to "Off") + PlaybackSettings.LANGUAGES).forEach { (code, name) ->
                            ReelButton(name, { app.playbackSettings.update { it.copy(subtitleLanguage = code) } }, kind = if (playback.subtitleLanguage == code) ButtonKind.Primary else ButtonKind.Ghost)
                        }
                    }
                    Text("Size", style = Tokens.Type.label, color = Tokens.Colors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        SubtitleSize.entries.forEach { size ->
                            ReelButton(size.label, { app.playbackSettings.update { it.copy(subtitleSize = size) } }, kind = if (playback.subtitleSize == size) ButtonKind.Primary else ButtonKind.Ghost)
                        }
                    }
                    if (state.addons.none { it.enabled && runCatching { it.manifest.hasSubtitles }.getOrDefault(false) }) {
                        Text("Subtitles come from sources and subtitle addons. OpenSubtitles v3 is the official, free one.", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                        ReelButton("Add OpenSubtitles v3", { run { app.sources.addAddon(OPENSUBTITLES).let { "Added ${it.manifest.name}" } } }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Add, enabled = !busy)
                    }
                }
            }

            item {
                Group(Icons.Rounded.Hub, "Addons", cardWidth, "Stremio addons answer “where can I play this?”. Paste the link that ends in manifest.json.") {
                    if (state.addons.isEmpty()) Text("No addons yet.", style = Tokens.Type.body, color = Tokens.Colors.textTertiary)
                    state.addons.forEach { addon ->
                        val m = runCatching { addon.manifest }.getOrNull()
                        ToggleRow(
                            m?.name ?: addon.url,
                            listOfNotNull(m?.version?.let { "v$it" }, if (m?.hasSubtitles == true && m.hasStreams != true) "subtitles" else null, m?.description).joinToString(" · ").ifBlank { addon.url },
                            addon.enabled,
                            onRemove = { app.sources.removeAddon(addon.url); message = "Removed ${m?.name ?: "addon"}" },
                        ) { app.sources.setAddonEnabled(addon.url, !addon.enabled) }
                    }
                    AddField(addonUrl, { addonUrl = it }, "Add addon", busy) {
                        run { app.sources.addAddon(addonUrl).let { addonUrl = ""; "Added ${it.manifest.name}" } }
                    }
                }
            }

            item {
                Group(Icons.Rounded.Extension, "Plugins", cardWidth, "Plugin repositories (Nuvio format) run small scripts on this device to find sources.") {
                    state.repos.forEach { repo ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = Tokens.Type.heading, color = Tokens.Colors.text)
                                Text(if (repo.builtIn) "Built in · v${repo.version}" else "v${repo.version} · ${repo.url}", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            GlassIconButton(Icons.Rounded.Refresh, "Update", { run { app.sources.addRepo(repo.url, repo.builtIn); "Updated ${repo.name}" } })
                            if (!repo.builtIn) GlassIconButton(Icons.Rounded.Delete, "Remove", { app.sources.removeRepo(repo.url); message = "Removed ${repo.name}" })
                        }
                        state.plugins.filter { it.repoUrl == repo.url }.forEach { p ->
                            ToggleRow(
                                p.name,
                                listOfNotNull("v${p.version}", p.types.joinToString(" + ") { if (it == "tv") "shows" else "films" }, p.description).joinToString(" · "),
                                p.enabled,
                            ) { app.sources.setPluginEnabled(p.repoUrl, p.id, !p.enabled) }
                        }
                    }
                    AddField(repoUrl, { repoUrl = it }, "Add repository", busy) {
                        run { app.sources.addRepo(repoUrl).let { repoUrl = ""; "Added ${it.name}" } }
                    }
                }
            }

            item {
                Group(Icons.Rounded.Storage, "Storage", cardWidth, "Streams are cached on this device and trimmed automatically.") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                        Pill("Stream cache · ${formatBytes(cacheBytes)}")
                        ReelButton("Clear", {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.engine.trimCache(0) }
                                cacheBytes = withContext(Dispatchers.IO) { app.engine.cacheBytes() }
                                message = "Stream cache cleared"
                            }
                        }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
                    }
                }
            }

            item {
                Group(Icons.Rounded.Info, "About", cardWidth) {
                    Text("Reel ${BuildConfig.VERSION_NAME}", style = Tokens.Type.label, color = Tokens.Colors.text)
                    Text(
                        "Reel ships only legal sources (open movies and public-domain films). Addons and plugins you add are your choice — " +
                            "streaming or downloading content you don't have rights to is illegal in most countries. Title data from TMDB; availability from JustWatch.",
                        style = Tokens.Type.caption, color = Tokens.Colors.textTertiary,
                    )
                }
            }
        }
    }
}

/** A titled card with an icon in a violet-tinted circle. */
@Composable
private fun Group(icon: ImageVector, title: String, modifier: Modifier, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ReelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Tokens.Colors.accent.copy(alpha = 0.16f)).border(Tokens.Size.hairline, Tokens.Colors.accent.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Tokens.Colors.accentBright, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text)
                if (subtitle != null) Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
            }
        }
        content()
    }
}

/** Whole row is one tap / focus target; the switch shows the state. */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onRemove: (() -> Unit)? = null, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Row(
            Modifier.weight(1f).focusCard(shape = RoundedCornerShape(Tokens.Radius.m), scale = 1.02f, onClick = onToggle).background(Tokens.Colors.surface2).padding(Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Tokens.Type.label, color = Tokens.Colors.text)
                Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Tokens.Colors.accent,
                    checkedThumbColor = Tokens.Colors.onAccent,
                    uncheckedTrackColor = Tokens.Colors.surface3,
                    uncheckedThumbColor = Tokens.Colors.textSecondary,
                    uncheckedBorderColor = Tokens.Colors.hairlineStrong,
                ),
            )
        }
        if (onRemove != null) GlassIconButton(Icons.Rounded.Delete, "Remove", onRemove)
    }
}

@Composable
private fun AddField(value: String, onChange: (String) -> Unit, button: String, busy: Boolean, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        OutlinedTextField(
            value, onChange, Modifier.weight(1f), singleLine = true,
            shape = RoundedCornerShape(Tokens.Radius.pill),
            placeholder = { Text("https://…/manifest.json") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Tokens.Colors.surface2, unfocusedContainerColor = Tokens.Colors.surface2,
                focusedBorderColor = Tokens.Colors.accentBright, unfocusedBorderColor = Tokens.Colors.hairlineStrong,
            ),
        )
        ReelButton(if (busy) "Adding…" else button, onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add, enabled = value.isNotBlank() && !busy)
    }
}
