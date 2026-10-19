package com.kirtanya.reel.ui.phone

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.nav.rememberNavigator
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.sources.SourcesScreen
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Phone root. Edge-to-edge screens; a floating glass bar (Home · Search · + · Downloads ·
 * Settings) on the main tabs; title, sources and collection pages stack on top full-bleed.
 */
@Composable
fun PhoneApp(incomingMagnet: String?, onIncomingHandled: () -> Unit, openDest: Dest? = null, onOpenHandled: () -> Unit = {}) {
    val nav = rememberNavigator()
    LaunchedEffect(openDest) {
        openDest?.let { nav.openOrReturn(it); onOpenHandled() }
    }
    val saveable = rememberSaveableStateHolder()
    var showAdd by remember { mutableStateOf(false) }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.Home) { nav.back() }
    val openTitle: (com.kirtanya.reel.core.tmdb.TitleSummary) -> Unit = { nav.open(Dest.Title(it.ref)) }

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        when (val top = nav.top) {
            is Dest.Title -> saveable.SaveableStateProvider(top.ref.toString()) {
                PhoneTitle(top.ref, onBack = { nav.back() }, onPlay = { s, e -> nav.open(Dest.Sources(top.ref, s, e)) })
            }
            is Dest.Sources -> SourcesScreen(top.ref, top.season, top.episode, isTv = false, onBack = { nav.back() })
            is Dest.Collection -> CollectionScreen(top, isTv = false, onBack = { nav.back() }, onOpen = openTitle)
            null -> saveable.SaveableStateProvider(nav.tab.name) {
                when (nav.tab) {
                    Tab.Home -> PhoneHome(onOpen = openTitle, onOpenDest = { nav.open(it) }, onSearch = { nav.select(Tab.Search) }, onDownloads = { nav.select(Tab.Downloads) })
                    Tab.Search -> PhoneSearch(onOpen = openTitle)
                    Tab.Downloads -> DownloadsScreen(isTv = false, onAdd = { showAdd = true })
                    Tab.Settings -> SettingsScreen(isTv = false)
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
            FloatingBar(selected = nav.tab, onSelect = { nav.select(it) }, onAdd = { showAdd = true })
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

/** The reference's floating dark bar, with Reel's glowing "+" (add a magnet / .torrent) in the middle. */
@Composable
private fun FloatingBar(selected: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit) {
    val engine = ReelApp.get(LocalContext.current).engine
    val torrents by engine.torrents.collectAsState()
    val downloading = torrents.any { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.METADATA }
    val shape = RoundedCornerShape(Tokens.Radius.xl)
    Box(
        Modifier
            .fillMaxWidth()
            // Fade the content out under the bar so it reads as floating.
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Tokens.Colors.canvas.copy(alpha = 0.85f)))
            .navigationBarsPadding()
            .padding(horizontal = Tokens.Space.l, vertical = Tokens.Space.m),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(shape)
                .background(Tokens.Colors.surface1.copy(alpha = 0.94f))
                .border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, shape)
                // A faint violet wash along the top edge, like the reference.
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to Tokens.Colors.accent.copy(alpha = 0.10f), 0.6f to Color.Transparent))
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarItem(Tab.Home, Icons.Rounded.Home, Icons.Outlined.Home, selected, onSelect, Modifier.weight(1f))
            BarItem(Tab.Search, Icons.Rounded.Search, Icons.Outlined.Search, selected, onSelect, Modifier.weight(1f))
            AddButton(onAdd)
            BarItem(Tab.Downloads, Icons.Rounded.Download, Icons.Outlined.Download, selected, onSelect, Modifier.weight(1f), badge = downloading)
            BarItem(Tab.Settings, Icons.Rounded.Settings, Icons.Outlined.Settings, selected, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BarItem(tab: Tab, filled: ImageVector, outlined: ImageVector, selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
    val on = tab == selected
    Column(
        modifier.focusCard(shape = RoundedCornerShape(Tokens.Radius.m), scale = 1f, onClick = { onSelect(tab) }).padding(vertical = Tokens.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            Icon(if (on) filled else outlined, contentDescription = tab.label, tint = if (on) Tokens.Colors.text else Tokens.Colors.textTertiary, modifier = Modifier.size(24.dp))
            if (badge) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(Tokens.Colors.accentBright))
        }
        Text(tab.label, style = Tokens.Type.caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)), color = if (on) Tokens.Colors.text else Tokens.Colors.textTertiary, maxLines = 1, softWrap = false)
    }
}

/** Centre action: gradient pill sitting in its own violet glow. */
@Composable
private fun AddButton(onAdd: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 76.dp, height = 64.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(Tokens.Colors.accent.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.width / 1.6f))
            },
        )
        Box(
            Modifier
                .size(width = 60.dp, height = 44.dp)
                .focusCard(shape = shape, onClick = onAdd)
                .background(Tokens.Gradients.brand)
                .border(Tokens.Size.hairline, Color.White.copy(alpha = 0.2f), shape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, contentDescription = "Add a magnet or .torrent", tint = Tokens.Colors.onAccent, modifier = Modifier.size(26.dp)) }
    }
}
