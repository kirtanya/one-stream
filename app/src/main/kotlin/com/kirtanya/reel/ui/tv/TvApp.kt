package com.kirtanya.reel.ui.tv

import com.kirtanya.reel.ui.common.rowStart
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import com.kirtanya.reel.ui.settings.TvSettingsPanel
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.DownloadsScreen
import com.kirtanya.reel.ui.common.AddLinkSheet
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.CollectionScreen
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.nav.Navigator
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.nav.rememberNavigator
import com.kirtanya.reel.ui.sources.SourcesScreen
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import com.kirtanya.reel.ui.common.focusCard
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.kirtanya.reel.ui.theme.Tokens

/** The left rail's width: an icon button with room for the selected marker on its left and breathing space on its right. */
val TvRailWidth = 88.dp

/**
 * Left inset for tab pages that pad their own content by [Tokens.Space.tvSafeH] (Search, Downloads): their first
 * item then lands at the same x as Home's first card (the rail's width + [TvHomeSide]).
 */
internal val TvPageInset = TvRailWidth + TvHomeSide - Tokens.Space.tvSafeH

/** TV root: remote-first. A slim icon rail on the left; title, sources and collections full screen; Settings as a side panel. */
@Composable
fun TvApp(
    incomingMagnet: String?,
    onIncomingHandled: () -> Unit,
    openDest: Dest? = null,
    onOpenHandled: () -> Unit = {},
    openTab: Tab? = null,
    onTabHandled: () -> Unit = {},
    openSearch: String? = null,
    onSearchHandled: () -> Unit = {},
    nav: Navigator = rememberNavigator(),
) {
    // Each screen keeps its state (search text, season, scroll) while you're on a page above it.
    val saveable = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(openDest) {
        openDest?.let { nav.openOrReturn(it); onOpenHandled() }
    }
    LaunchedEffect(openTab) {
        openTab?.let { nav.select(it); onTabHandled() }
    }
    // Settings is a panel over the current screen, not a page: whatever selects the Settings tab (the rail, an
    // intent, a render) opens the panel and leaves the tab it was on in place.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tabBehind by remember { mutableStateOf(Tab.Home) }
    LaunchedEffect(nav.tab) {
        if (nav.tab == Tab.Settings) {
            settingsOpen = true
            nav.tab = tabBehind
        } else {
            tabBehind = nav.tab
        }
    }
    val settingsFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    // Closing the panel: the remote goes back to the rail's Settings icon (its own node left with the panel).
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            repeat(10) {
                withFrameNanos { }
                if (runCatching { settingsFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(openSearch) {
        openSearch?.let { nav.openSearch(it); onSearchHandled() }
    }
    // Settings → Home screen → Open on: once per launch, unless something asked for a screen.
    var started by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (openDest == null && openTab == null && openSearch == null && nav.stack.isEmpty() && nav.tab == Tab.Home) {
            when (com.kirtanya.reel.ui.home.HomeLayout.tvStart) {
                com.kirtanya.reel.ui.home.TvStart.Search -> nav.select(Tab.Search)
                com.kirtanya.reel.ui.home.TvStart.Downloads -> nav.select(Tab.Downloads)
                com.kirtanya.reel.ui.home.TvStart.Home -> Unit
            }
        }
    }
    val idle = remember { IdleWatch() }
    // False until the first frame is composed: the screen the app opens on appears at once, later screens fade in.
    val appeared = remember { booleanArrayOf(false) }
    val activity = LocalContext.current as? Activity
    // While the settings panel is open it owns Back (a sub-page, then the panel). Otherwise Back on Home goes to
    // Search, and on Search the first Back shows "Press Back again to exit" (as in the player); a second one within
    // [EXIT_WINDOW_MS] closes the app. Pages and the other tabs go back as before.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(EXIT_WINDOW_MS); exitArmed = false } }
    LaunchedEffect(nav.tab, nav.top) { exitArmed = false }
    var focusContent by remember { mutableStateOf(0) }
    LaunchedEffect(focusContent) {
        if (focusContent == 0) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    BackHandler(enabled = !settingsOpen) {
        when {
            nav.top != null -> nav.back()
            nav.tab == Tab.Home -> { nav.select(Tab.Search); focusContent++ }
            nav.tab != Tab.Search -> { nav.back(); focusContent++ }
            exitArmed -> activity?.finish()
            else -> exitArmed = true
        }
    }
    val openTitle: (com.kirtanya.reel.core.tmdb.TitleSummary) -> Unit = { nav.open(Dest.Title(it.ref)) }

    // Remote only: no 48 dp touch padding around controls (it would only push layouts apart).
    // An outer host wins (screen renders put one in to show the Undo state).
    val undo = com.kirtanya.reel.ui.common.LocalUndo.current ?: com.kirtanya.reel.ui.common.rememberUndoHost()
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
        com.kirtanya.reel.ui.common.LocalIsTv provides true,
        com.kirtanya.reel.ui.common.LocalUndo provides undo,
    ) {
    val back: () -> Unit = { nav.back() }
    val open: (Dest) -> Unit = { nav.open(it) }
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas).idleWatch(idle)) {
        // Screen to screen: the old one goes at once, the new one fades in over [TvMotion.pageEnterMs]. A plain `when`
        // keyed on the screen (not AnimatedContent): no second screen composed during the fade, no transition scope; each
        // screen still saves its state (SaveableStateProvider) when it leaves, and gets it back on Back.
        val screen = nav.top ?: if (nav.tab == Tab.Settings) tabBehind else nav.tab
        androidx.compose.runtime.key(screen) {
        PageEnter(animate = appeared[0]) {
            when (screen) {
                is Dest.Title -> saveable.SaveableStateProvider("title:${screen.ref}") {
                    TvTitle(screen.ref, screen.season, onPlay = { s, e -> nav.open(Dest.Sources(screen.ref, s, e)) }, onOpen = openTitle, onOpenDest = open)
                }
                is Dest.Sources -> SourcesScreen(screen.ref, screen.season, screen.episode, isTv = true, onBack = back, download = screen.download, replacing = screen.replacing, resumeAtMs = screen.resumeAtMs)
                is Dest.Collection -> saveable.SaveableStateProvider(com.kirtanya.reel.ui.phone.collectionKey(screen)) { CollectionScreen(screen, isTv = true, onBack = back, onOpen = openTitle) }
                is Dest.Person -> saveable.SaveableStateProvider("person:${screen.id}") { com.kirtanya.reel.ui.common.PersonScreen(screen, isTv = true, onBack = back, onOpen = openTitle) }
                is Dest.ImdbTitle -> com.kirtanya.reel.ui.title.ImdbTitleLookup(screen, isTv = true, onBack = back, onResolved = { nav.replace(screen, Dest.Title(it)) })
                is Dest.Cast -> com.kirtanya.reel.ui.sources.CastScreen(
                    screen.request, isTv = true, onBack = back,
                    onStarted = { nav.replace(screen, Dest.Title(screen.request.ref)) },
                    onOpenSources = { nav.replace(screen, Dest.Sources(screen.request.ref, screen.request.season, screen.request.episode)) },
                )
                is Tab -> saveable.SaveableStateProvider(if (screen == Tab.Search) nav.searchStateKey else screen.name) {
                    // ► from the rail comes back to the card you left (focusRestorer).
                    // While the settings panel is open the screen behind is only to look at: the remote is held in the
                    // panel (its focus trap), and semantics say so (a modal's background is disabled).
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (settingsOpen) Modifier.semantics { disabled() } else Modifier)
                            .focusRequester(contentFocus).focusRestorer(),
                    ) {
                        when (screen) {
                            Tab.Home -> TvHome(onOpen = openTitle, onOpenDest = open, onShowDownloads = { nav.select(Tab.Downloads) })
                            Tab.Search -> Box(Modifier.padding(start = TvPageInset)) { TvSearch(onOpen = openTitle, onOpenDest = open, initialQuery = nav.searchQuery) }
                            Tab.Downloads -> Box(Modifier.padding(start = TvPageInset, top = Tokens.Space.tvSafeV)) { DownloadsScreen(isTv = true, onAdd = {}, onOpenDest = open) }
                            // Never shown: selecting it opens the panel (above) and the screen behind stays.
                            Tab.Settings -> Unit
                        }
                    }
                }
            }
        }
        }
        SideEffect { appeared[0] = true }
        // The rail stays put while tabs change under it.
        if (nav.top == null) {
            TvRail(
                selected = if (settingsOpen) Tab.Settings else nav.tab,
                onSelect = { tab -> if (tab == Tab.Settings) settingsOpen = true else { settingsOpen = false; nav.select(tab) } },
                settingsFocus = settingsFocus, contentFocus = contentFocus,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        if (settingsOpen) TvSettingsPanel(onClose = { settingsOpen = false }, Modifier.align(Alignment.CenterEnd))
        com.kirtanya.reel.ui.common.UndoSnackbarHost(
            undo, isTv = true,
            Modifier.align(Alignment.BottomStart).padding(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV),
        )
        if (exitArmed) TvExitChip(Modifier.align(Alignment.BottomCenter).padding(bottom = Tokens.Space.tvSafeV))
        // Browse screens only (the player is its own activity; Sources may be mid-search).
        TvScreensaver(idle, enabled = nav.top == null || nav.top is Dest.Title || nav.top is Dest.Collection)
    }
    }

    // A magnet sent to the TV (e.g. opened from another app): Stream / Download sheet.
    if (incomingMagnet != null) AddLinkSheet(initialLink = incomingMagnet, onDismiss = onIncomingHandled, onDownloadStarted = { nav.select(Tab.Downloads) })
}

private const val EXIT_WINDOW_MS = 3_000L

/** "Press Back again to exit", bottom centre: the same words and pill as the player's exit chip. */
@Composable
private fun TvExitChip(modifier: Modifier = Modifier) {
    Text(
        "Press Back again to exit",
        modifier.background(Tokens.Colors.surface3, CircleShape).border(Tokens.Size.hairline, Tokens.Colors.hairline, CircleShape)
            .padding(horizontal = Tokens.Space.l, vertical = Tokens.Space.s),
        style = Tokens.Type.body, color = Tokens.Colors.text,
    )
}

/**
 * The TV's left rail: four icons (Search, Home, Downloads, Settings), vertically centred. Selected = white
 * icon + a small marker on its left, the rest grey; focus = a ring and a white icon. It's one focus group: ◄ from a
 * screen's first item enters on the selected icon, ► leaves to the screen ([contentFocus], which remembers where you
 * were), ▲ / ▼ past the ends stay put. Nothing animates (the ring fade is the shared focus fade).
 *
 * While any icon has focus the rail EXPANDS: each tab's name appears beside its icon on a solid panel. It is an overlay:
 * the rail keeps reporting [TvRailWidth] to its parent (the wrapper below lets the panel overflow to the right) so no
 * page moves, and the panel is drawn in the rail's own draw pass (no blur, no shadow, no layer, no animation).
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun TvRail(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    settingsFocus: FocusRequester = remember { FocusRequester() },
    contentFocus: FocusRequester? = null,
) {
    val items = remember { listOf(Tab.Search to Icons.Rounded.Search, Tab.Home to Icons.Rounded.Home, Tab.Downloads to Icons.Rounded.Download, Tab.Settings to Icons.Rounded.Settings) }
    val requesters = remember { items.associate { (tab, _) -> tab to (if (tab == Tab.Settings) settingsFocus else FocusRequester()) } }
    var expanded by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val panelPad = with(density) { Tokens.Space.s.toPx() }
    val panelCorner = with(density) { Tokens.Radius.l.toPx() }
    val railPanelColor = Tokens.Colors.surface1
    // Outside: exactly the collapsed footprint, so the page beside it never moves. Inside the panel may be wider.
    Box(modifier.width(TvRailWidth).wrapContentWidth(Alignment.Start, unbounded = true)) {
        Column(
            Modifier
                .widthIn(min = TvRailWidth)
                .drawBehind {
                    if (expanded) {
                        // Left corners start off-screen so only the right edge reads as rounded; it grows past the icons by
                        // [panelPad] above and below so the first and last icon don't touch the edge.
                        drawRoundRect(
                            color = railPanelColor,
                            topLeft = Offset(-panelCorner, -panelPad),
                            size = Size(size.width + panelCorner, size.height + 2 * panelPad),
                            cornerRadius = CornerRadius(panelCorner),
                        )
                    }
                }
                .focusProperties {
                    enter = { requesters.getValue(selected) }
                    // Only the four arrows are held in or sent on; a programmatic focus change (Exit) must pass, or a page that
                    // lost focus could never hand it back.
                    exit = { dir ->
                        when (dir) {
                            FocusDirection.Right -> contentFocus ?: FocusRequester.Cancel
                            FocusDirection.Left, FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
                            else -> FocusRequester.Default
                        }
                    }
                }
                .onFocusChanged { expanded = it.hasFocus }
                .focusGroup()
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            items.forEach { (tab, icon) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The selected marker: 3 × 16 dp, drawn only for the selected icon.
                    Box(Modifier.width(3.dp).height(16.dp).then(if (tab == selected) Modifier.clip(CircleShape).background(Tokens.Colors.text) else Modifier))
                    Spacer(Modifier.width(9.dp))
                    RailIcon(icon, tab.label, selected = tab == selected, Modifier.focusRequester(requesters.getValue(tab))) { onSelect(tab) }
                    if (expanded) {
                        Text(
                            tab.label,
                            modifier = Modifier.padding(start = Tokens.Space.m, end = Tokens.Space.xl),
                            style = Tokens.Type.heading,
                            color = if (tab == selected) Tokens.Colors.text else Tokens.Colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // Read here only: four small composables redraw on a focus move, nothing else.
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(Tokens.Size.iconButton)
            .onFocusChanged { focused = it.isFocused }
            .focusCard(shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected || focused) Tokens.Colors.text else Tokens.Colors.textSecondary, modifier = Modifier.size(22.dp))
    }
}

/**
 * A text tab (a title's seasons, Search's All / Films / Shows, "See all"): text only, an optional icon; selected =
 * white with a short underline, the rest grey; focus = the ring.
 */
@Composable
internal fun TvTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    val fg = if (selected) Tokens.Colors.text else Tokens.Colors.textSecondary
    Box(
        modifier.focusCard(shape = shape, onClick = onClick).height(36.dp).padding(horizontal = Tokens.Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Text(label, style = if (selected) TabSelected else TabIdle, color = fg, maxLines = 1)
        }
        // The underline sits inside the pill, above the ring, so focus + selected both read.
        if (selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).width(24.dp).height(3.dp).clip(shape).background(Tokens.Colors.text))
    }
}

// Built once: a `.copy()` per tab per recomposition is garbage on a 32-bit box.
private val TabSelected = Tokens.Type.heading
private val TabIdle = Tokens.Type.heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)

/**
 * The screen's fade-in: opacity only, on the content's own layer (no overlay, no zoom). `ModulateAlpha` scales each draw op's
 * alpha instead of rendering the screen into an offscreen buffer first, which is what a plain `alpha` would cost on the TV's GPU.
 */
@Composable
private fun PageEnter(animate: Boolean, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(Unit) { alpha.animateTo(1f, tween(TvMotion.pageEnterMs, easing = TvMotion.easing)) }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) { content() }
}
