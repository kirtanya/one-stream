package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sources (addons + plugin repos) and storage. Same screen on phone and TV. */
@Composable
fun SettingsScreen(isTv: Boolean) {
    val app = ReelApp.get(LocalContext.current)
    val state by app.sources.state.collectAsState()
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
    LazyColumn(
        Modifier.fillMaxSize().background(Tokens.Colors.bg),
        contentPadding = PaddingValues(horizontal = side, vertical = Tokens.Space.l),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        item { Text("Settings", style = Tokens.Type.display, color = Tokens.Colors.text) }
        item {
            Note(
                "Reel ships only legal sources (open movies and public-domain films). " +
                    "Addons and plugins you add are your choice — streaming or downloading content you don't have rights to is illegal in most countries.",
            )
        }
        message?.let { item { Text(it, style = Tokens.Type.body, color = Tokens.Colors.accent) } }

        item { Section("Addons", "Stremio addons answer “where can I play this?” over the internet. Paste the link that ends in manifest.json.") }
        if (state.addons.isEmpty()) item { Text("No addons yet.", style = Tokens.Type.body, color = Tokens.Colors.textMuted) }
        items(state.addons, key = { it.url }) { addon ->
            val m = runCatching { addon.manifest }.getOrNull()
            ToggleRow(
                title = m?.name ?: addon.url,
                subtitle = listOfNotNull(m?.version?.let { "v$it" }, m?.description).joinToString(" · ").ifBlank { addon.url },
                checked = addon.enabled,
                onToggle = { app.sources.setAddonEnabled(addon.url, !addon.enabled) },
                onRemove = { app.sources.removeAddon(addon.url); message = "Removed ${m?.name ?: "addon"}" },
            )
        }
        item {
            AddField(addonUrl, { addonUrl = it }, "https://…/manifest.json", "Add addon", busy) {
                run { app.sources.addAddon(addonUrl).let { addonUrl = ""; "Added ${it.manifest.name}" } }
            }
        }

        item { Section("Plugins", "Plugin repositories (Nuvio format) run small scripts on this device to find sources.") }
        state.repos.forEach { repo ->
            item(key = "repo:${repo.url}") {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        Column(Modifier.weight(1f)) {
                            Text(repo.name, style = Tokens.Type.heading, color = Tokens.Colors.text)
                            Text(if (repo.builtIn) "Built in · v${repo.version}" else "v${repo.version} · ${repo.url}", style = Tokens.Type.caption, color = Tokens.Colors.textMuted, maxLines = 1)
                        }
                        ReelButton("", { run { app.sources.addRepo(repo.url, repo.builtIn); "Updated ${repo.name}" } }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh, enabled = !busy)
                        if (!repo.builtIn) ReelButton("", { app.sources.removeRepo(repo.url); message = "Removed ${repo.name}" }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
                    }
                }
            }
            items(state.plugins.filter { it.repoUrl == repo.url }, key = { "plugin:${it.repoUrl}:${it.id}" }) { p ->
                ToggleRow(
                    title = p.name,
                    subtitle = listOfNotNull("v${p.version}", p.types.joinToString(" + ") { if (it == "tv") "shows" else "films" }, p.description).joinToString(" · "),
                    checked = p.enabled,
                    onToggle = { app.sources.setPluginEnabled(p.repoUrl, p.id, !p.enabled) },
                )
            }
        }
        item {
            AddField(repoUrl, { repoUrl = it }, "https://…/manifest.json", "Add repository", busy) {
                run { app.sources.addRepo(repoUrl).let { repoUrl = ""; "Added ${it.name}" } }
            }
        }

        item { Section("Storage", "Streams are cached on this device and trimmed automatically.") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                Text("Stream cache: ${formatBytes(cacheBytes)}", style = Tokens.Type.body, color = Tokens.Colors.text)
                ReelButton("Clear", {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.engine.trimCache(0) }
                        cacheBytes = withContext(Dispatchers.IO) { app.engine.cacheBytes() }
                        message = "Stream cache cleared"
                    }
                }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
            }
        }
        item { Text("Reel ${BuildConfig.VERSION_NAME}", style = Tokens.Type.caption, color = Tokens.Colors.textMuted, modifier = Modifier.padding(top = Tokens.Space.xl)) }
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Column(Modifier.padding(top = Tokens.Space.l), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
        Text(title, style = Tokens.Type.title, color = Tokens.Colors.text)
        Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textMuted)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = Tokens.Type.caption,
        color = Tokens.Colors.textMuted,
        modifier = Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(Tokens.Radius.m)).background(Tokens.Colors.surface).padding(Tokens.Space.m),
    )
}

/** Whole row is one focus target / tap target; the switch just shows the state. */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Row(
            Modifier.weight(1f).widthIn(max = 900.dp).focusCard(onClick = onToggle).background(Tokens.Colors.surface).padding(Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Tokens.Type.label, color = Tokens.Colors.text)
                Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textMuted, maxLines = 2)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = Tokens.Colors.accent, checkedThumbColor = Tokens.Colors.onAccent),
            )
        }
        if (onRemove != null) ReelButton("", onRemove, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
    }
}

@Composable
private fun AddField(value: String, onChange: (String) -> Unit, placeholder: String, button: String, busy: Boolean, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), modifier = Modifier.widthIn(max = 900.dp)) {
        OutlinedTextField(value, onChange, Modifier.weight(1f), singleLine = true, placeholder = { Text(placeholder) })
        ReelButton(if (busy) "Adding…" else button, onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add, enabled = value.isNotBlank() && !busy)
    }
}

