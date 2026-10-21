package com.kirtanya.reel.ui.tv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.sources.SourcesScreen
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.pageTransition
import com.kirtanya.reel.ui.theme.Tokens

/** Height reserved at the top of every TV tab for the tab bar (it sits inside the overscan-safe area). */
val TvTopBarHeight = Tokens.Space.tvSafeV + Tokens.Size.button + Tokens.Space.s

/** TV root: remote-first. A glass tab bar across the top; title, sources and collections full screen. */
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
    LaunchedEffect(openSearch) {
        openSearch?.let { nav.openSearch(it); onSearchHandled() }
    }
    val activity = LocalContext.current as? Activity
    BackHandler { if (!nav.back()) activity?.finish() }
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
    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        // Screen to screen: a short cross-fade with a slight zoom; each screen keeps its state underneath.
        androidx.compose.animation.AnimatedContent(nav.top ?: nav.tab, transitionSpec = { pageTransition() }, label = "screen") { screen ->
            when (screen) {
                is Dest.Title -> saveable.SaveableStateProvider("title:${screen.ref}") {
                    TvTitle(screen.ref, onPlay = { s, e -> nav.open(Dest.Sources(screen.ref, s, e)) }, onOpen = openTitle, onOpenDest = open)
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
                    when (screen) {
                        Tab.Home -> TvHome(onOpen = openTitle, onOpenDest = open)
                        Tab.Search -> TvSearch(onOpen = openTitle, onOpenDest = open, initialQuery = nav.searchQuery)
                        Tab.Downloads -> Box(Modifier.padding(top = TvTopBarHeight)) { DownloadsScreen(isTv = true, onAdd = {}, onOpenDest = open) }
                        Tab.Settings -> Box(Modifier.padding(top = TvTopBarHeight)) { SettingsScreen(isTv = true) }
                    }
                }
            }
        }
        // The tab bar stays put while tabs change under it.
        if (nav.top == null) TvTopBar(nav)
        com.kirtanya.reel.ui.common.UndoSnackbarHost(
            undo, isTv = true,
            Modifier.align(Alignment.BottomStart).padding(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV),
        )
    }
    }

    // A magnet sent to the TV (e.g. opened from another app): Stream / Download sheet.
    if (incomingMagnet != null) AddLinkSheet(initialLink = incomingMagnet, onDismiss = onIncomingHandled, onDownloadStarted = { nav.select(Tab.Downloads) })
}

@Composable
internal fun TvTopBar(nav: Navigator) {
    Row(
        Modifier.fillMaxWidth().height(TvTopBarHeight).padding(horizontal = Tokens.Space.tvSafeH).padding(top = Tokens.Space.tvSafeV, bottom = Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wordmark: the One Stream mark + name.
        com.kirtanya.reel.ui.common.BrandMark(32.dp)
        Spacer(Modifier.width(Tokens.Space.s))
        Text("One Stream", style = Tokens.Type.title, color = Tokens.Colors.text)
        Spacer(Modifier.width(Tokens.Space.xl))
        val shape = RoundedCornerShape(Tokens.Radius.pill)
        Row(
            Modifier.clip(shape).background(Tokens.Colors.glass).padding(Tokens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xxs),
        ) {
            listOf(Tab.Home to Icons.Rounded.Home, Tab.Search to Icons.Rounded.Search, Tab.Downloads to Icons.Rounded.Download, Tab.Settings to Icons.Rounded.Settings)
                .forEach { (tab, icon) -> TvTab(tab.label, icon, selected = nav.tab == tab) { nav.select(tab) } }
        }
    }
}

/** A top tab: the selected one is a filled primary pill, the rest plain; focus lifts it with a ring. */
@Composable
private fun TvTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    val fg = if (selected) Tokens.Colors.onAccent else Tokens.Colors.textSecondary
    Row(
        Modifier
            .focusCard(shape = shape, scale = 1.04f, onClick = onClick)
            .background(if (selected) Tokens.Colors.accent else Color.Transparent)
            .height(36.dp)
            .padding(horizontal = Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, style = Tokens.Type.label, color = fg)
    }
}
