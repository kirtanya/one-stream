package com.kirtanya.reel.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.ReelCard
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.store.AddonStorePage
import com.kirtanya.reel.ui.theme.AccentPreset
import com.kirtanya.reel.ui.theme.AccentState
import com.kirtanya.reel.ui.theme.ThemeMode
import androidx.compose.ui.graphics.luminance
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"

/** Settings pages. The root lists them in groups; each opens on its own. */
enum class SettingsPage(val title: String, val icon: ImageVector) {
    Appearance("Appearance", Icons.Rounded.Palette),
    Playback("Playback", Icons.Rounded.PlayCircle),
    Subtitles("Subtitles", Icons.Rounded.Subtitles),
    Addons("Addons", Icons.Rounded.Hub),
    Store("Addon store", Icons.Rounded.Storefront),
    Plugins("Plugins", Icons.Rounded.Extension),
    PhoneTv("Phone & TV", Icons.Rounded.Devices),
    Storage("Storage", Icons.Rounded.Storage),
    About("About", Icons.Rounded.Info),
}

private val GROUPS = listOf(
    "Watching" to listOf(SettingsPage.Playback, SettingsPage.Subtitles),
    "Sources" to listOf(SettingsPage.Addons, SettingsPage.Store, SettingsPage.Plugins),
    "Devices" to listOf(SettingsPage.PhoneTv),
    "App" to listOf(SettingsPage.Appearance, SettingsPage.Storage, SettingsPage.About),
)

/**
 * Settings: a short grouped list (Watching, Sources, Devices, App), each row showing where it
 * stands, opening a page of its own. Back (button on the phone, remote on TV) goes up a level
 * and puts focus back on the row you came from.
 */
@Composable
fun SettingsScreen(isTv: Boolean) {
    // Pages open on top of each other (Addons → Addon store); saved as names so rotation keeps them.
    var stackNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val stack = stackNames.map { SettingsPage.valueOf(it) }
    var cameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var forward by remember { mutableStateOf(true) }
    fun open(page: SettingsPage) {
        forward = true
        cameFrom = null
        stackNames = stackNames + page.name
    }
    fun back() {
        forward = false
        cameFrom = stackNames.lastOrNull()
        stackNames = stackNames.dropLast(1)
    }
    BackHandler(enabled = stack.isNotEmpty()) { back() }

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        AmbientGlow(Tokens.Colors.accent, strength = 0.25f)
        AnimatedContent(
            stack.lastOrNull(),
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(Tokens.Motion.stateMs)) { it / 6 * dir } + fadeIn(tween(Tokens.Motion.stateMs))) togetherWith
                    (slideOutHorizontally(tween(Tokens.Motion.stateMs)) { -it / 6 * dir } + fadeOut(tween(Tokens.Motion.stateMs / 2)))
            },
            label = "settingsPage",
        ) { page ->
            when (page) {
                null -> SettingsHome(isTv, focusOn = cameFrom?.let { SettingsPage.valueOf(it) }, onOpen = ::open)
                SettingsPage.Store -> AddonStorePage(isTv, onBack = ::back)
                else -> SettingsDetail(page, isTv, onOpen = ::open, onBack = ::back)
            }
        }
    }
}

// ─── Root ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsHome(isTv: Boolean, focusOn: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val sources by app.sources.state.collectAsState()
    val playback by app.playbackSettings.state.collectAsState()
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { app.engine.cacheBytes() } }
    val devices = if (isTv) {
        val tv by app.tvSync.state.collectAsState()
        tv.pairedPhones.joinToString { it.name }.ifEmpty { null }?.let { "Paired with $it" } ?: "Pair with your phone to get its sources"
    } else {
        val phone by app.phoneSync.state.collectAsState()
        phone.tvs.joinToString { it.name }.ifEmpty { null }?.let { "Paired with $it" } ?: "Send your sources to a TV"
    }

    fun summary(page: SettingsPage): String = when (page) {
        SettingsPage.Appearance -> listOfNotNull(
            if (isTv) null else "${AccentState.themeMode.label} theme",
            AccentState.preset.label + " colour",
            if (AccentState.adaptive) "matches what's on screen" else null,
        ).joinToString(" · ")
        SettingsPage.Playback -> if (playback.autoplayNext) "Next episode plays by itself" else "Next episode: ask first"
        SettingsPage.Subtitles -> listOf(
            PlaybackSettings.LANGUAGES.firstOrNull { it.first == playback.subtitleLanguage }?.second ?: "Off",
            playback.subtitleSize.label,
        ).joinToString(" · ")
        SettingsPage.Addons -> sources.addons.size.let { if (it == 0) "None yet" else "$it added · ${sources.addons.count { a -> a.enabled }} on" }
        SettingsPage.Store -> "Find addons and add them in one tap"
        SettingsPage.Plugins -> "${sources.plugins.count { it.enabled }} of ${sources.plugins.size} plugins on"
        SettingsPage.PhoneTv -> devices
        SettingsPage.Storage -> if (cacheBytes < 0) "Stream cache" else "Stream cache · ${formatBytes(cacheBytes)}"
        SettingsPage.About -> "Reel ${BuildConfig.VERSION_NAME}"
    }

    val requesters = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    // Coming back up a level on TV: the remote lands on the row you opened.
    if (isTv && focusOn != null) RequestFocusOnce(requesters.getValue(focusOn), key = focusOn)

    PageList(isTv) {
        item { PageTitle("Settings", null, isTv, onBack = null) }
        GROUPS.forEach { (group, pages) ->
            item(key = group) {
                Column(Modifier.widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                    GroupLabel(group)
                    ReelCard(Modifier.fillMaxWidth(), padding = Tokens.Space.xs, spacing = 0.dp) {
                        pages.forEachIndexed { i, page ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = Tokens.Space.l), thickness = Tokens.Size.hairline, color = Tokens.Colors.hairline)
                            NavRow(page.icon, page.title, summary(page), Modifier.focusRequester(requesters.getValue(page))) { onOpen(page) }
                        }
                    }
                }
            }
        }
    }
}

/** Material list-group label: sentence case, primary colour. */
@Composable
private fun GroupLabel(text: String) {
    Text(text, style = Tokens.Type.label, color = Tokens.Colors.accent, modifier = Modifier.padding(start = Tokens.Space.l, top = Tokens.Space.s))
}

/** One row of the root list: icon tile, name, where it stands, chevron. The whole row opens the page. */
@Composable
private fun NavRow(icon: ImageVector, title: String, summary: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusCard(shape = RoundedCornerShape(Tokens.Radius.m), scale = 1.02f, onClick = onClick).padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Tokens.Type.label, color = Tokens.Colors.text)
            Text(summary, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tokens.Colors.textTertiary)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Tokens.Colors.tonal),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Tokens.Colors.onTonal, modifier = Modifier.size(20.dp)) }
}

// ─── Page frame ────────────────────────────────────────────────────────────────────────────

/** The scrolling column every settings page uses (phone clears the bottom bar; TV keeps safe margins). */
@Composable
internal fun PageList(isTv: Boolean, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = side, end = side,
            top = if (isTv) Tokens.Space.l else 0.dp,
            bottom = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.bottomBarClearance + Tokens.Space.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        content = content,
    )
}

/**
 * Page heading. On the phone a back button sits before it; the TV uses the remote's Back, so it
 * shows where you are instead ("Settings ›").
 */
@Composable
internal fun PageTitle(title: String, subtitle: String?, isTv: Boolean, onBack: (() -> Unit)?) {
    Row(
        Modifier.then(if (isTv) Modifier else Modifier.statusBarsPadding()).padding(top = Tokens.Space.l).widthIn(max = 860.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        if (onBack != null && !isTv) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        Column(Modifier.weight(1f)) {
            if (onBack != null && isTv) Text("Settings  ›", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
            Text(title, style = if (onBack == null) Tokens.Type.display else Tokens.Type.title, color = Tokens.Colors.text)
            if (subtitle != null) Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
        }
    }
}

/** A plain card for a page's content (no title: the page has one). */
@Composable
private fun Section(title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        if (title != null) GroupLabel(title)
        ReelCard(Modifier.fillMaxWidth(), content = content)
        if (note != null) Text(note, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, modifier = Modifier.padding(horizontal = Tokens.Space.xs))
    }
}

// ─── Pages ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsDetail(page: SettingsPage, isTv: Boolean, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val sources by app.sources.state.collectAsState()
    val playback by app.playbackSettings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    if (page == SettingsPage.Storage) LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { app.engine.cacheBytes() } }

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

    // TV: the remote starts on the page's first control.
    val first = remember { FocusRequester() }
    if (isTv) RequestFocusOnce(first)

    val subtitle = when (page) {
        SettingsPage.Appearance -> "Light or dark, Reel's colour, and whether it follows what you're watching."
        SettingsPage.Addons -> "Stremio addons answer “where can I play this?”."
        SettingsPage.Plugins -> "Plugin repositories (Nuvio format) run small scripts on this device to find sources."
        SettingsPage.PhoneTv -> if (isTv) "Get addons and plugins from your phone, so you never type on the TV." else "Set sources up once on the phone; your TV gets them too."
        SettingsPage.Storage -> "Streams are cached on this device and trimmed automatically."
        else -> null
    }

    PageList(isTv, Modifier.focusRequester(first)) {
        item { PageTitle(page.title, subtitle, isTv, onBack) }
        message?.let { m -> item { Text(m, style = Tokens.Type.label, color = Tokens.Colors.accentBright) } }

        when (page) {
            SettingsPage.Appearance -> {
                if (!isTv) item {
                    Section("Theme", note = "System follows your phone's dark-mode setting.") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { i, mode ->
                                SegmentedButton(
                                    selected = AccentState.themeMode == mode,
                                    onClick = { AccentState.chooseTheme(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                ) { Text(mode.label, style = Tokens.Type.label) }
                            }
                        }
                    }
                }
                item {
                    Section("Colour", note = if (AccentState.systemSeed != null) "System uses your phone's accent colour (from the wallpaper)." else null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                            AccentPreset.entries.filter { it != AccentPreset.System || AccentState.systemSeed != null }.forEach { preset ->
                                Swatch(preset, selected = AccentState.preset == preset) { AccentState.choose(preset) }
                            }
                        }
                    }
                }
                item {
                    Section(note = "Off: Reel always uses the colour above.") {
                        ToggleRow(
                            "Match what's on screen",
                            "The whole screen takes the colours of the film or show you're looking at.",
                            AccentState.adaptive,
                        ) { AccentState.matchScreen(!AccentState.adaptive) }
                    }
                }
                item { Section("Preview") { AccentPreview() } }
            }

            SettingsPage.Playback -> item {
                Section {
                    ToggleRow(
                        "Play the next episode automatically",
                        "Finds a source near the end of an episode and starts it after a 10-second countdown.",
                        playback.autoplayNext,
                    ) { app.playbackSettings.update { it.copy(autoplayNext = !it.autoplayNext) } }
                }
            }

            SettingsPage.Subtitles -> {
                item {
                    Section("Language") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            (listOf<Pair<String?, String>>(null to "Off") + PlaybackSettings.LANGUAGES).forEach { (code, name) ->
                                ReelButton(name, { app.playbackSettings.update { it.copy(subtitleLanguage = code) } }, kind = if (playback.subtitleLanguage == code) ButtonKind.Primary else ButtonKind.Ghost)
                            }
                        }
                    }
                }
                item {
                    Section("Size") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                            SubtitleSize.entries.forEach { size ->
                                ReelButton(size.label, { app.playbackSettings.update { it.copy(subtitleSize = size) } }, kind = if (playback.subtitleSize == size) ButtonKind.Primary else ButtonKind.Ghost)
                            }
                        }
                    }
                }
                if (sources.addons.none { it.enabled && runCatching { it.manifest.hasSubtitles }.getOrDefault(false) }) item {
                    Section(note = "Subtitles come from sources and subtitle addons. OpenSubtitles v3 is the official, free one.") {
                        ReelButton("Add OpenSubtitles v3", { run { app.sources.addAddon(OPENSUBTITLES).let { "Added ${it.manifest.name}" } } }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Add, enabled = !busy)
                    }
                }
            }

            SettingsPage.Addons -> {
                item {
                    ReelCard(Modifier.widthIn(max = 860.dp).fillMaxWidth(), padding = Tokens.Space.xs) {
                        NavRow(Icons.Rounded.Storefront, "Browse the addon store", "Search addons and add one in a tap") { onOpen(SettingsPage.Store) }
                    }
                }
                item {
                    Section("Added", note = if (sources.addons.isEmpty()) null else "Switch one off to stop asking it for sources.") {
                        if (sources.addons.isEmpty()) Text("No addons yet.", style = Tokens.Type.body, color = Tokens.Colors.textTertiary)
                        sources.addons.forEach { addon ->
                            val m = runCatching { addon.manifest }.getOrNull()
                            ToggleRow(
                                m?.name ?: addon.url,
                                listOfNotNull(m?.version?.let { "v$it" }, if (m?.hasSubtitles == true && m.hasStreams != true) "subtitles" else null, m?.description).joinToString(" · ").ifBlank { addon.url },
                                addon.enabled,
                                onRemove = { app.sources.removeAddon(addon.url); message = "Removed ${m?.name ?: "addon"}" },
                            ) { app.sources.setAddonEnabled(addon.url, !addon.enabled) }
                        }
                    }
                }
                item {
                    Section("Add by link", note = "Tap Install on an addon's website, or paste its link (manifest.json or stremio://).") {
                        AddField(link, { link = it }, "Add", busy) { run { app.sources.addFromLink(link).also { link = "" } } }
                    }
                }
            }

            SettingsPage.Plugins -> {
                sources.repos.forEach { repo ->
                    item(key = repo.url) {
                        Section {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                Column(Modifier.weight(1f)) {
                                    Text(repo.name, style = Tokens.Type.heading, color = Tokens.Colors.text)
                                    Text(if (repo.builtIn) "Built in · v${repo.version}" else "v${repo.version} · ${repo.url}", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                GlassIconButton(Icons.Rounded.Refresh, "Update ${repo.name}", { run { app.sources.addRepo(repo.url, repo.builtIn); "Updated ${repo.name}" } })
                                if (!repo.builtIn) GlassIconButton(Icons.Rounded.Delete, "Remove ${repo.name}", { app.sources.removeRepo(repo.url); message = "Removed ${repo.name}" })
                            }
                            sources.plugins.filter { it.repoUrl == repo.url }.forEach { p ->
                                ToggleRow(
                                    p.name,
                                    listOfNotNull("v${p.version}", p.types.joinToString(" + ") { if (it == "tv") "shows" else "films" }, p.description).joinToString(" · "),
                                    p.enabled,
                                ) { app.sources.setPluginEnabled(p.repoUrl, p.id, !p.enabled) }
                            }
                        }
                    }
                }
                item {
                    Section("Add a repository") {
                        AddField(link, { link = it }, "Add", busy) { run { app.sources.addFromLink(link).also { link = "" } } }
                    }
                }
            }

            // Composed only while this page is open: the TV offers a pairing code just while it's on screen.
            SettingsPage.PhoneTv -> item { Section { SyncContent(isTv) } }

            SettingsPage.Storage -> item {
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                        Column(Modifier.weight(1f)) {
                            Text("Stream cache", style = Tokens.Type.label, color = Tokens.Colors.text)
                            Text(formatBytes(cacheBytes), style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                        }
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

            SettingsPage.About -> item {
                Section {
                    Text("Reel ${BuildConfig.VERSION_NAME}", style = Tokens.Type.heading, color = Tokens.Colors.text, modifier = Modifier.focusCard(scale = 1f) {})
                    Text(
                        "Reel ships only legal sources (open movies and public-domain films). Addons and plugins you add are your choice — " +
                            "streaming or downloading content you don't have rights to is illegal in most countries.",
                        style = Tokens.Type.body, color = Tokens.Colors.textSecondary,
                    )
                    Text("Title data from TMDB; availability from JustWatch. Font: Instrument Sans (SIL Open Font Licence).", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                }
            }

            SettingsPage.Store -> Unit
        }
    }
}

/** One colour choice: a circle in the colour it gives the app, ringed and ticked when chosen. */
@Composable
private fun Swatch(preset: AccentPreset, selected: Boolean, onClick: () -> Unit) {
    val dark = AccentState.scheme.surface.luminance() < 0.5f
    val primary = remember(preset, dark) { AccentState.build(AccentState.seedOf(preset), dark, media = false).primary }
    val onPrimary = remember(preset, dark) { AccentState.build(AccentState.seedOf(preset), dark, media = false).onPrimary }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Box(
            Modifier.size(44.dp)
                .focusCard(shape = CircleShape, onClick = onClick)
                .background(primary)
                .border(Tokens.Size.focusBorder, if (selected) Tokens.Colors.text else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Rounded.Check, "${preset.label}, chosen", tint = onPrimary, modifier = Modifier.size(20.dp)) }
        Text(preset.label, style = Tokens.Type.caption, color = if (selected) Tokens.Colors.text else Tokens.Colors.textTertiary)
    }
}

/** What the accent touches: the main button, a tag, progress. Follows the live accent. */
@Composable
private fun AccentPreview() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        ReelButton("Play", {}, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
        Pill("Trending", accent = true)
    }
    GradientProgress(0.6f, Modifier.fillMaxWidth())
    Text("Resume · 42 min left", style = Tokens.Type.caption, color = Tokens.Colors.accentBright)
}

// ─── Shared controls (also used by the Phone & TV card and the addon store) ───────────────

/** Whole row is one tap / focus target; the switch shows the state. */
@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onRemove: (() -> Unit)? = null, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Row(
            Modifier.weight(1f).focusCard(shape = RoundedCornerShape(Tokens.Radius.m), scale = 1.02f, onClick = onToggle).padding(vertical = Tokens.Space.s, horizontal = Tokens.Space.xs),
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

/** Full-width field with its button underneath (on a phone the URL needs the whole width). */
@Composable
internal fun AddField(
    value: String,
    onChange: (String) -> Unit,
    button: String,
    busy: Boolean,
    placeholder: String = "https://…/manifest.json",
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    ready: Boolean = value.isNotBlank(),
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(Tokens.Radius.pill),
            placeholder = { Text(placeholder) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Tokens.Colors.surface2, unfocusedContainerColor = Tokens.Colors.surface2,
                focusedBorderColor = Tokens.Colors.accentBright, unfocusedBorderColor = Tokens.Colors.hairlineStrong,
            ),
        )
        ReelButton(if (busy) "…" else button, onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add, enabled = ready && !busy)
    }
}
