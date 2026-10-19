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
import com.kirtanya.reel.ui.theme.Tokens

/** Height reserved at the top of every TV tab for the tab bar. */
val TvTopBarHeight = 96.dp

/** TV root: remote-first. A glass tab bar across the top; title, sources and collections full screen. */
@Composable
fun TvApp(incomingMagnet: String?, onIncomingHandled: () -> Unit, openDest: Dest? = null, onOpenHandled: () -> Unit = {}) {
    val nav = rememberNavigator()
    LaunchedEffect(openDest) {
        openDest?.let { nav.openOrReturn(it); onOpenHandled() }
    }
    val activity = LocalContext.current as? Activity
    BackHandler { if (!nav.back()) activity?.finish() }
    val openTitle: (com.kirtanya.reel.core.tmdb.TitleSummary) -> Unit = { nav.open(Dest.Title(it.ref)) }

    Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
        when (val top = nav.top) {
            is Dest.Title -> TvTitle(top.ref, onPlay = { s, e -> nav.open(Dest.Sources(top.ref, s, e)) })
            is Dest.Sources -> SourcesScreen(top.ref, top.season, top.episode, isTv = true, onBack = { nav.back() })
            is Dest.Collection -> CollectionScreen(top, isTv = true, onBack = { nav.back() }, onOpen = openTitle)
            null -> {
                when (nav.tab) {
                    Tab.Home -> TvHome(onOpen = openTitle, onOpenDest = { nav.open(it) })
                    Tab.Search -> TvSearch(onOpen = openTitle)
                    Tab.Downloads -> Box(Modifier.padding(top = TvTopBarHeight)) { DownloadsScreen(isTv = true, onAdd = {}) }
                    Tab.Settings -> Box(Modifier.padding(top = TvTopBarHeight)) { SettingsScreen(isTv = true) }
                }
                TvTopBar(nav)
            }
        }
    }

    // A magnet sent to the TV (e.g. opened from another app): Stream / Download sheet.
    if (incomingMagnet != null) AddLinkSheet(initialLink = incomingMagnet, onDismiss = onIncomingHandled, onDownloadStarted = { nav.select(Tab.Downloads) })
}

@Composable
private fun TvTopBar(nav: Navigator) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wordmark: a small glowing dot + "Reel".
        Box(Modifier.size(12.dp).clip(CircleShape).background(Tokens.Gradients.brand))
        Spacer(Modifier.width(Tokens.Space.s))
        Text("Reel", style = Tokens.Type.title, color = Tokens.Colors.text)
        Spacer(Modifier.width(Tokens.Space.xl))
        val shape = RoundedCornerShape(Tokens.Radius.pill)
        Row(
            Modifier.clip(shape).background(Tokens.Colors.glass).border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, shape).padding(Tokens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
        ) {
            listOf(Tab.Home to Icons.Rounded.Home, Tab.Search to Icons.Rounded.Search, Tab.Downloads to Icons.Rounded.Download, Tab.Settings to Icons.Rounded.Settings)
                .forEach { (tab, icon) ->
                    ReelButton(tab.label, { nav.select(tab) }, kind = if (nav.tab == tab) ButtonKind.Primary else ButtonKind.Ghost, icon = icon)
                }
        }
    }
}
