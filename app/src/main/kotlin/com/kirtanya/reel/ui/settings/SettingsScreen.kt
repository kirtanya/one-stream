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
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
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
import androidx.compose.ui.unit.sp
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.sync.SourceLinks
import com.kirtanya.reel.ui.common.AddSourceLinksSheet
import com.kirtanya.reel.data.PlaybackSettings
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.ui.common.AmbientGlow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.ReelCard
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

private const val OPENSUBTITLES = com.kirtanya.reel.core.store.BuiltInAddons.OPENSUBTITLES_URL

/** Settings pages. The root lists them in groups; each opens on its own. */
enum class SettingsPage(val title: String, val icon: ImageVector) {
    Appearance("Appearance", Icons.Rounded.Palette),
    Playback("Playback", Icons.Rounded.PlayCircle),
    Subtitles("Subtitles", Icons.Rounded.Subtitles),
    History("History", Icons.Rounded.History),
    SourcePrefs("Source preferences", Icons.Rounded.Tune),
    Addons("Addons", Icons.Rounded.Hub),
    Store("Addon store", Icons.Rounded.Storefront),
    Plugins("Plugins", Icons.Rounded.Extension),
    PhoneTv("Phone & TV", Icons.Rounded.Devices),
    HomeStorage("Home storage", Icons.Rounded.Dns),
    HomeLayout("Home screen", Icons.Rounded.Dashboard),
    Storage("Storage", Icons.Rounded.Storage),
    About("About", Icons.Rounded.Info),
}

private val GROUPS = listOf(
    "Watching" to listOf(SettingsPage.Playback, SettingsPage.Subtitles, SettingsPage.History),
    "Sources" to listOf(SettingsPage.SourcePrefs, SettingsPage.Addons, SettingsPage.Store, SettingsPage.Plugins),
    "Devices" to listOf(SettingsPage.PhoneTv, SettingsPage.HomeStorage),
    "App" to listOf(SettingsPage.Appearance, SettingsPage.HomeLayout, SettingsPage.Storage, SettingsPage.About),
)

/**
 * Puts the remote on [requester] when a page opens, and keeps asking for a few frames. The page that held focus has just left
 * composition, and Android hands focus back to the first focusable of the window (the rail) a frame or two later, which would
 * otherwise win; this is the same retry the options panel uses.
 */
@Composable
internal fun PanelFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        repeat(4) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { requester.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }
            kotlinx.coroutines.delay(Tokens.Motion.focusMs.toLong())
        }
    }
}

/** True inside the TV's side panel ([TvSettingsPanel]): narrower margins, smaller page title. */
internal val LocalSettingsPanel = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Settings: a short grouped list (Watching, Sources, Devices, App), each row showing where it
 * stands, opening a page of its own. Back (button on the phone, remote on TV) goes up a level
 * and puts focus back on the row you came from. [onClose] set = shown in the TV's side panel ([TvSettingsPanel]):
 * Back on the root list closes the panel, and the panel's own surface shows (this screen draws no background).
 */
@Composable
fun SettingsScreen(isTv: Boolean, start: List<SettingsPage> = emptyList(), onClose: (() -> Unit)? = null) {
    // Pages open on top of each other (Addons → Addon store); saved as names so rotation keeps them.
    var stackNames by rememberSaveable { mutableStateOf(start.map { it.name }) }
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
    // One handler for both levels: two handlers would depend on which one registered last.
    BackHandler(enabled = stack.isNotEmpty() || onClose != null) { if (stack.isNotEmpty()) back() else onClose?.invoke() }
    // Opened from elsewhere (the Download-where sheet, adb): jump straight to that page.
    val link = SettingsLink.pending
    androidx.compose.runtime.LaunchedEffect(link) {
        if (link != null) {
            forward = true
            stackNames = listOf(link.name)
            SettingsLink.pending = null
        }
    }

    val pageContent: @Composable (SettingsPage?) -> Unit = { page ->
        when (page) {
            null -> SettingsHome(isTv, focusOn = cameFrom?.let { SettingsPage.valueOf(it) }, onOpen = ::open)
            SettingsPage.Store -> AddonStorePage(isTv, onBack = ::back)
            else -> SettingsDetail(page, isTv, onOpen = ::open, onBack = ::back)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsPanel provides (onClose != null)) {
    Box(Modifier.fillMaxSize().then(if (onClose != null) Modifier else Modifier.background(Tokens.Colors.canvas))) {
        if (isTv) {
            // TV: pages swap at once (a slide + fade of a full page is lag on the weak chip, and AnimatedContent would
            // keep the old page composed for a frame); the whole screen's own fade-in is TvApp's.
            androidx.compose.runtime.key(stack.lastOrNull()) { pageContent(stack.lastOrNull()) }
        } else {
            AmbientGlow(Tokens.Colors.accent, strength = 0.25f)
            AnimatedContent(
                stack.lastOrNull(),
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(Tokens.Motion.stateMs)) { it / 6 * dir } + fadeIn(tween(Tokens.Motion.stateMs))) togetherWith
                        (slideOutHorizontally(tween(Tokens.Motion.stateMs)) { -it / 6 * dir } + fadeOut(tween(Tokens.Motion.stateMs / 2)))
                },
                label = "settingsPage",
            ) { page -> pageContent(page) }
        }
    }
    }
}

// ─── Root ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsHome(isTv: Boolean, focusOn: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val sources = installedSources(app)
    val playback by app.playbackSettings.state.collectAsState()
    val home = com.kirtanya.reel.ui.homedisk.rememberHomeView()
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
        ).joinToString(" · ")
        SettingsPage.Playback -> if (playback.autoplayNext) "Next episode plays by itself" else "Next episode: ask first"
        SettingsPage.Subtitles -> listOf(
            PlaybackSettings.LANGUAGES.firstOrNull { it.first == playback.subtitleLanguage }?.second ?: "Off",
            playback.caption.size.label,
            if (playback.caption.system) "Android style" else com.kirtanya.reel.core.playback.CaptionStyle.Preset.of(playback.caption)?.label ?: "Custom style",
        ).joinToString(" · ")
        SettingsPage.Addons -> sources.addons.size.let { if (it == 0) "None yet" else "$it added · ${sources.addons.count { a -> a.enabled }} on" }
        SettingsPage.Store -> "Find addons and add them in one tap"
        SettingsPage.Plugins -> "${sources.plugins.count { it.enabled }} of ${sources.plugins.size} plugins on"
        SettingsPage.PhoneTv -> devices
        SettingsPage.Storage -> if (cacheBytes < 0) "Stream cache" else "Stream cache · ${formatBytes(cacheBytes)}"
        SettingsPage.HomeStorage -> homeStorageSummary(home, isTv)
        SettingsPage.HomeLayout -> homeLayoutSummary(isTv)
        SettingsPage.History -> historySummary(app)
        SettingsPage.SourcePrefs -> sourcePrefsSummary(app.sourcePrefs.state.value)
        SettingsPage.About -> "One Stream ${BuildConfig.VERSION_NAME}"
    }

    val requesters = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    // Coming back up a level on TV: the remote lands on the row you opened.
    if (isTv && focusOn != null) PanelFocus(requesters.getValue(focusOn), key = focusOn)
    // In the side panel the remote starts on the first row.
    if (isTv && focusOn == null && LocalSettingsPanel.current) PanelFocus(requesters.getValue(GROUPS.first().second.first()))

    PageList(isTv) {
        item { PageTitle("Settings", null, isTv, onBack = null) }
        // The TV is always black & white, so it has no Appearance page.
        GROUPS.map { (group, pages) -> group to pages.filter { !isTv || it != SettingsPage.Appearance } }.forEach { (group, pages) ->
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

/** Installed addons, repos and plugins (fixed data in screen renders). */
@Composable
internal fun installedSources(app: ReelApp): com.kirtanya.reel.data.SourcesState {
    val live by app.sources.state.collectAsState()
    return com.kirtanya.reel.ui.common.LocalRenderData.current?.installed ?: live
}

/** Material list-group label: sentence case, primary colour. */
@Composable
internal fun GroupLabel(text: String) {
    if (com.kirtanya.reel.ui.common.LocalIsTv.current) {
        // TV: a quiet small-caps header (Netflix / Hotstar settings), so the rows under it carry the weight.
        Text(text.uppercase(), style = TvGroupLabel, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(start = Tokens.Space.s, top = Tokens.Space.m))
    } else {
        Text(text, style = Tokens.Type.label, color = Tokens.Colors.accent, modifier = Modifier.padding(start = Tokens.Space.l, top = Tokens.Space.s))
    }
}

private val TvGroupLabel = Tokens.Type.caption.copy(letterSpacing = 1.4.sp)
private val TvSummary = Tokens.Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)

/** One row of the root list: icon tile, name, where it stands, chevron. The whole row opens the page. */
@Composable
private fun NavRow(icon: ImageVector, title: String, summary: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tv = com.kirtanya.reel.ui.common.LocalIsTv.current
    Row(
        modifier.fillMaxWidth().focusCard(shape = RoundedCornerShape(Tokens.Radius.m), onClick = onClick)
            .padding(horizontal = Tokens.Space.m, vertical = if (tv) Tokens.Space.m + Tokens.Space.xxs else Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            // TV: a size up, readable from the sofa.
            Text(title, style = if (tv) Tokens.Type.heading else Tokens.Type.label, color = Tokens.Colors.text)
            Text(summary, style = if (tv) TvSummary else Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val panel = LocalSettingsPanel.current
    val side = if (panel) Tokens.Space.xl else if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
    val list: @Composable () -> Unit = {
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = side, end = side,
                top = if (isTv) (if (panel) Tokens.Space.tvSafeV else Tokens.Space.l) else 0.dp,
                bottom = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.bottomBarClearance + Tokens.Space.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            content = content,
        )
    }
    // TV: the page glides to the focused row over the shared glide time (a long page of rows, no spring).
    if (isTv) com.kirtanya.reel.ui.tv.TvColumnGlide(reveal = Tokens.Space.l, content = list) else list()
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
            if (onBack != null && isTv) Text("Settings  ›", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
            // TV: the page name is the hero line, like a Netflix / Hotstar settings page.
            Text(title, style = if (isTv && onBack == null && !LocalSettingsPanel.current) Tokens.Type.hero else if (onBack == null) Tokens.Type.display else Tokens.Type.title, color = Tokens.Colors.text)
            if (subtitle != null) Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
        }
    }
}

/** A plain card for a page's content (no title: the page has one). */
@Composable
internal fun Section(title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        if (title != null) GroupLabel(title)
        ReelCard(Modifier.fillMaxWidth(), content = content)
        if (note != null) Text(note, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(horizontal = Tokens.Space.xs))
    }
}

// ─── Pages ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsDetail(page: SettingsPage, isTv: Boolean, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val sources = installedSources(app)
    val playback by app.playbackSettings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    // Several links pasted at once: they open the pick-which sheet instead of adding straight away.
    var several by remember { mutableStateOf<List<String>?>(null) }
    several?.let { urls -> AddSourceLinksSheet(urls, isTv) { several = null } }
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

    fun addLinks() {
        val urls = SourceLinks.allFromUserInput(link)
        if (urls.size > 1) {
            several = urls
            link = ""
        } else run { app.sources.addFromLink(link).also { link = "" } }
    }

    // Results ("Added …", errors) as a toast: the field that caused them is often at the bottom of the page.
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); message = null }
    }

    // TV: the remote starts on the page's first control.
    val first = remember { FocusRequester() }
    if (isTv) PanelFocus(first, key = page)

    val subtitle = when (page) {
        SettingsPage.Appearance -> "Light or dark, and the app's colour."
        SettingsPage.Addons -> "Stremio addons answer “where can I play this?”."
        SettingsPage.Plugins -> "Plugin repositories (Nuvio format) run small scripts on this device to find sources."
        SettingsPage.PhoneTv -> if (isTv) "Get addons and plugins from your phone, so you never type on the TV." else "Set sources up once on the phone; your TV gets them too."
        SettingsPage.Storage -> "Streams are cached on this device and trimmed automatically."
        SettingsPage.HomeStorage -> "A USB disk on your router: your library and stream cache, shared by your devices."
        else -> null
    }

    PageList(isTv, Modifier.focusRequester(first)) {
        item { PageTitle(page.title, subtitle, isTv, onBack) }

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
                item { Section("Preview") { AccentPreview() } }
            }

            SettingsPage.Playback -> playbackItems(isTv)

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
                captionStyleItems(playback.caption, isTv) { style -> app.playbackSettings.update { it.copy(caption = style) } }
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
                                m?.name ?: (android.net.Uri.parse(addon.url).host ?: addon.url),
                                if (m == null) "Couldn't read this addon · ${addon.url}"
                                else listOfNotNull(m.version.let { "v$it" }, if (m.hasSubtitles && !m.hasStreams) "subtitles" else null, m.description).joinToString(" · ").ifBlank { addon.url },
                                addon.enabled,
                                onRemove = { app.sources.removeAddon(addon.url); message = "Removed ${m?.name ?: "addon"}" },
                            ) { app.sources.setAddonEnabled(addon.url, !addon.enabled) }
                        }
                    }
                }
                item {
                    Section("Add by link", note = "Tap Install on an addon's website, or paste its link (manifest.json or stremio://). Paste several, one per line, to add them together.") {
                        AddField(link, { link = it }, "Add", busy, multiLine = true, onAdd = ::addLinks)
                    }
                }
            }

            SettingsPage.Plugins -> {
                sources.repos.forEach { repo ->
                    item(key = repo.url) {
                        Section {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                Column(Modifier.weight(1f)) {
                                    Text(repo.name, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        AddField(link, { link = it }, "Add", busy, multiLine = true, onAdd = ::addLinks)
                    }
                }
            }

            // Composed only while this page is open: the TV offers a pairing code just while it's on screen.
            SettingsPage.PhoneTv -> {
                item { Section { SyncContent(isTv) } }
                if (!isTv) item { Section("TV remote") { TvRemoteContent() } }
            }

            SettingsPage.HomeStorage -> homeStorageItems(isTv)
            SettingsPage.HomeLayout -> homeLayoutItems(isTv)
            SettingsPage.History -> historyItems()
            SettingsPage.SourcePrefs -> sourcePrefsItems(isTv)

            SettingsPage.Storage -> {
                item {
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
    
                autoDownloadItems(isTv)
                downloadRulesItems(isTv)
            }
            SettingsPage.About -> item {
                Section {
                    Text("One Stream ${BuildConfig.VERSION_NAME}", style = Tokens.Type.heading, color = Tokens.Colors.text, modifier = Modifier.focusCard {})
                    Text(
                        "One Stream ships only legal sources (open movies and public-domain films). Addons and plugins you add are your choice — " +
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
    val primary = remember(preset, dark) { AccentState.schemeFor(preset, dark).primary }
    val onPrimary = remember(preset, dark) { AccentState.schemeFor(preset, dark).onPrimary }
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
            Modifier.weight(1f).focusCard(shape = RoundedCornerShape(Tokens.Radius.m), onClick = onToggle).padding(vertical = Tokens.Space.s, horizontal = Tokens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    /** Grows to a few lines so a pasted list of links stays readable. */
    multiLine: Boolean = false,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = !multiLine, maxLines = if (multiLine) 5 else 1, keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(if (multiLine) Tokens.Radius.l else Tokens.Radius.pill),
            placeholder = { Text(placeholder) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Tokens.Colors.surface2, unfocusedContainerColor = Tokens.Colors.surface2,
                focusedBorderColor = Tokens.Colors.accentBright, unfocusedBorderColor = Tokens.Colors.hairlineStrong,
            ),
        )
        ReelButton(if (busy) "…" else button, onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add, enabled = ready && !busy)
    }
}
