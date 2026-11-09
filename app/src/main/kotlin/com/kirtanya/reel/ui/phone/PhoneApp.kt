package com.kirtanya.reel.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.DownloadsScreen
import com.kirtanya.reel.ui.common.AddLinkSheet
import com.kirtanya.reel.ui.common.CollectionScreen
import com.kirtanya.reel.ui.common.PersonScreen
import com.kirtanya.reel.ui.common.pageTransition
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.nav.rememberNavigator
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.sources.SourcesScreen
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Phone root. Edge-to-edge screens; the floating tab bar (Home · Search · Downloads · Settings) on
 * the main tabs; title, sources, grids and person pages stack on top full-bleed, cross-fading in.
 * [nav] can be passed in to open a particular screen (screen renders do).
 */
@Composable
fun PhoneApp(
    incomingMagnet: String?,
    onIncomingHandled: () -> Unit,
    openDest: Dest? = null,
    onOpenHandled: () -> Unit = {},
    openTab: Tab? = null,
    onTabHandled: () -> Unit = {},
    openSearch: String? = null,
    onSearchHandled: () -> Unit = {},
    nav: com.kirtanya.reel.ui.nav.Navigator = rememberNavigator(),
) {
    LaunchedEffect(openDest) {
        openDest?.let { nav.openOrReturn(it); onOpenHandled() }
    }
    LaunchedEffect(openTab) {
        openTab?.let { nav.select(it); onTabHandled() }
    }
    LaunchedEffect(openSearch) {
        openSearch?.let { nav.openSearch(it); onSearchHandled() }
    }
    val saveable = rememberSaveableStateHolder()
    var showAdd by remember { mutableStateOf(false) }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.Home) { nav.back() }
    val openTitle: (com.kirtanya.reel.core.tmdb.TitleSummary) -> Unit = { nav.open(Dest.Title(it.ref)) }

    val back: () -> Unit = { nav.back() }
    val open: (Dest) -> Unit = { nav.open(it) }

    // An outer host wins (screen renders put one in to show the Undo state).
    val undo = com.kirtanya.reel.ui.common.LocalUndo.current ?: com.kirtanya.reel.ui.common.rememberUndoHost()
    androidx.compose.runtime.CompositionLocalProvider(com.kirtanya.reel.ui.common.LocalUndo provides undo) {
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        // Screen to screen: a cross-fade with a slight zoom. Each screen keeps its state (scroll,
        // season, search) while something is on top of it.
        AnimatedContent(nav.top ?: nav.tab, transitionSpec = {
            // Tab to tab (Netflix): a quick cross-fade, no zoom; pages stacking on top zoom in as before.
            if (initialState is Tab && targetState is Tab) fadeIn(tween(180)) togetherWith fadeOut(tween(90))
            else pageTransition()
        }, label = "screen") { screen ->
            when (screen) {
                is Dest.Title -> saveable.SaveableStateProvider("title:${screen.ref}") {
                    PhoneTitle(screen.ref, screen.season, onBack = back, onPlay = { s, e -> nav.open(Dest.Sources(screen.ref, s, e)) }, onOpen = openTitle, onOpenDest = open)
                }
                is Dest.Sources -> SourcesScreen(screen.ref, screen.season, screen.episode, isTv = false, onBack = back, download = screen.download, replacing = screen.replacing, resumeAtMs = screen.resumeAtMs)
                is Dest.Collection -> saveable.SaveableStateProvider(collectionKey(screen)) { CollectionScreen(screen, isTv = false, onBack = back, onOpen = openTitle) }
                is Dest.Person -> saveable.SaveableStateProvider("person:${screen.id}") { PersonScreen(screen, isTv = false, onBack = back, onOpen = openTitle) }
                // Categories is a TV page; the phone never opens one.
                is Dest.Category -> Unit
                is Dest.ImdbTitle -> com.kirtanya.reel.ui.title.ImdbTitleLookup(screen, isTv = false, onBack = back, onResolved = { nav.replace(screen, Dest.Title(it)) })
                is Dest.Cast -> com.kirtanya.reel.ui.sources.CastScreen(
                    screen.request, isTv = false, onBack = back,
                    onStarted = { nav.replace(screen, Dest.Title(screen.request.ref)) },
                    onOpenSources = { nav.replace(screen, Dest.Sources(screen.request.ref, screen.request.season, screen.request.episode)) },
                )
                is Tab -> saveable.SaveableStateProvider(if (screen == Tab.Search) nav.searchStateKey else screen.name) {
                    when (screen) {
                        Tab.Home -> PhoneHome(onOpen = openTitle, onOpenDest = open, onAdd = { showAdd = true })
                        Tab.Search -> PhoneSearch(onOpen = openTitle, onOpenDest = open, initialQuery = nav.searchQuery)
                        Tab.Downloads -> DownloadsScreen(isTv = false, onAdd = { showAdd = true }, onOpenDest = open)
                        Tab.Settings -> SettingsScreen(isTv = false)
                        // TV-only tabs: the phone's bar has no button for them.
                        Tab.Movies, Tab.Shows, Tab.Categories -> Unit
                    }
                }
            }
        }

        // Tab screens scroll under the status bar: a light canvas fade keeps it readable.
        if (nav.stack.isEmpty()) com.kirtanya.reel.ui.common.TopFade(extra = 8.dp)

        AnimatedVisibility(
            visible = nav.stack.isEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BottomBar(selected = nav.tab, onSelect = { nav.select(it) })
        }
        // Undo for removals (Continue watching): just above the floating tab bar, or the gesture bar on pages.
        com.kirtanya.reel.ui.common.UndoSnackbarHost(
            undo, isTv = false,
            Modifier.align(Alignment.BottomCenter).padding(horizontal = Tokens.Space.phoneGutter)
                .padding(bottom = if (nav.stack.isEmpty()) Tokens.Space.bottomBarClearance + Tokens.Space.s else Tokens.Space.l)
                .then(if (nav.stack.isEmpty()) Modifier else Modifier.navigationBarsPadding()),
        )
    }
    }

    if (showAdd || incomingMagnet != null) {
        AddLinkSheet(
            initialLink = incomingMagnet,
            onDismiss = {
                showAdd = false
                onIncomingHandled()
            },
            onDownloadStarted = { nav.select(Tab.Downloads) },
        )
    }
}

/** Saved-state key for a grid: two genres can share a name (Drama films, Drama shows). */
internal fun collectionKey(d: Dest.Collection) = "collection:${d.title}:${d.provider?.id}:${d.genre?.id}:${d.genreType}"

/**
 * Docked tab bar (Netflix / Hotstar): full width, four tabs with an icon and a label each, on a solid raised
 * surface with a hairline on top. The selected tab is filled and in the text colour, the rest outlined and
 * quiet; a short bar on the bar's top edge slides to the selected tab (drawn, not recomposed). Solid: no blur,
 * no shadow, so it costs nothing to scroll under. The icon + label are the tab's accessible name.
 */
@Composable
internal fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val engine = ReelApp.get(LocalContext.current).engine
    val torrents by engine.torrents.collectAsState()
    val downloading = torrents.any { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA }
    val tabs = listOf(
        Triple(Tab.Home, Icons.Rounded.Home, Icons.Outlined.Home),
        Triple(Tab.Search, Icons.Rounded.Search, Icons.Outlined.Search),
        Triple(Tab.Downloads, Icons.Rounded.Download, Icons.Outlined.Download),
        Triple(Tab.Settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
    )
    val index = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { position.animateTo(index.toFloat(), tween(SLIDE_MS, easing = EmphasisedDecelerate)) }
    val line = Tokens.Colors.hairline
    val marker = Tokens.Colors.accent
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Colors.surface1)
            .drawBehind {
                drawRect(line, size = androidx.compose.ui.geometry.Size(size.width, Tokens.Size.hairline.toPx()))
                // Selected-tab marker: slides with the animation value, read here in the draw phase.
                val slot = size.width / tabs.size
                val w = 28.dp.toPx()
                val h = 3.dp.toPx()
                drawRoundRect(
                    marker, Offset(position.value * slot + (slot - w) / 2f, 0f), androidx.compose.ui.geometry.Size(w, h),
                    androidx.compose.ui.geometry.CornerRadius(h / 2f),
                )
            }
            .navigationBarsPadding()
            .selectableGroup(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            tabs.forEach { (tab, filled, outlined) ->
                val on = tab == selected
                val tint by animateColorAsState(
                    if (on) Tokens.Colors.text else Tokens.Colors.textSecondary,
                    tween(PRESS_MS),
                    label = "tab tint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BadgedBox(badge = { if (tab == Tab.Downloads && downloading) Badge(containerColor = Tokens.Colors.accent) }) {
                        Icon(if (on) filled else outlined, contentDescription = null, tint = tint, modifier = Modifier.size(Tokens.Size.barIcon))
                    }
                    Text(tab.label, style = Tokens.Type.caption, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
