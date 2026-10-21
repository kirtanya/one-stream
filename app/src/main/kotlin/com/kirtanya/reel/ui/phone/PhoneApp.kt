package com.kirtanya.reel.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.engine.TorrentPhase
import com.kirtanya.reel.ui.DownloadsScreen
import com.kirtanya.reel.ui.common.AddLinkSheet
import com.kirtanya.reel.ui.common.CollectionScreen
import com.kirtanya.reel.ui.common.PersonScreen
import com.kirtanya.reel.ui.common.pageTransition
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.nav.rememberNavigator
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.sources.SourcesScreen
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Phone root. Edge-to-edge screens; the navigation bar (Home · Search · Downloads · Settings) on
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

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        // Screen to screen: a cross-fade with a slight zoom. Each screen keeps its state (scroll,
        // season, search) while something is on top of it.
        AnimatedContent(nav.top ?: nav.tab, transitionSpec = { pageTransition() }, label = "screen") { screen ->
            when (screen) {
                is Dest.Title -> saveable.SaveableStateProvider("title:${screen.ref}") {
                    PhoneTitle(screen.ref, onBack = back, onPlay = { s, e -> nav.open(Dest.Sources(screen.ref, s, e)) }, onOpen = openTitle, onOpenDest = open)
                }
                is Dest.Sources -> SourcesScreen(screen.ref, screen.season, screen.episode, isTv = false, onBack = back, download = screen.download, replacing = screen.replacing)
                is Dest.Collection -> saveable.SaveableStateProvider(collectionKey(screen)) { CollectionScreen(screen, isTv = false, onBack = back, onOpen = openTitle) }
                is Dest.Person -> saveable.SaveableStateProvider("person:${screen.id}") { PersonScreen(screen, isTv = false, onBack = back, onOpen = openTitle) }
                is Dest.ImdbTitle -> com.kirtanya.reel.ui.title.ImdbTitleLookup(screen, isTv = false, onBack = back, onResolved = { nav.replace(screen, Dest.Title(it)) })
                is Tab -> saveable.SaveableStateProvider(if (screen == Tab.Search) nav.searchStateKey else screen.name) {
                    when (screen) {
                        Tab.Home -> PhoneHome(onOpen = openTitle, onOpenDest = open, onAdd = { showAdd = true })
                        Tab.Search -> PhoneSearch(onOpen = openTitle, onOpenDest = open, initialQuery = nav.searchQuery)
                        Tab.Downloads -> DownloadsScreen(isTv = false, onAdd = { showAdd = true }, onOpenDest = open)
                        Tab.Settings -> SettingsScreen(isTv = false)
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

/** Material 3 navigation bar: four destinations, a pill behind the selected one. */
@Composable
internal fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val engine = ReelApp.get(LocalContext.current).engine
    val torrents by engine.torrents.collectAsState()
    val downloading = torrents.any { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA }
    NavigationBar(containerColor = Tokens.Colors.surface1, tonalElevation = 0.dp) {
        listOf(
            Triple(Tab.Home, Icons.Rounded.Home, Icons.Outlined.Home),
            Triple(Tab.Search, Icons.Rounded.Search, Icons.Outlined.Search),
            Triple(Tab.Downloads, Icons.Rounded.Download, Icons.Outlined.Download),
            Triple(Tab.Settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
        ).forEach { (tab, filled, outlined) ->
            val on = tab == selected
            NavigationBarItem(
                selected = on,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (tab == Tab.Downloads && downloading) Badge(containerColor = Tokens.Colors.accent) }) {
                        Icon(if (on) filled else outlined, contentDescription = null)
                    }
                },
                label = { Text(tab.label, style = Tokens.Type.caption, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Tokens.Colors.onTonal,
                    selectedTextColor = Tokens.Colors.text,
                    indicatorColor = Tokens.Colors.tonal,
                    unselectedIconColor = Tokens.Colors.textSecondary,
                    unselectedTextColor = Tokens.Colors.textSecondary,
                ),
            )
        }
    }
}
