package com.kirtanya.reel.ui.tv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.EngineScreen
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.sources.SourcesScreen
import com.kirtanya.reel.ui.nav.Navigator
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.nav.rememberNavigator
import com.kirtanya.reel.ui.theme.Tokens

/** Height reserved at the top of every TV tab for the tab bar. */
val TvTopBarHeight = 80.dp

/** TV root: remote-first. Tabs across the top, title pages full screen. */
@Composable
fun TvApp(incomingMagnet: String?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavigator()
    val activity = LocalContext.current as? Activity
    BackHandler { if (!nav.back()) activity?.finish() }

    Box(Modifier.fillMaxSize().background(Tokens.Colors.bg)) {
        when (val top = nav.top) {
            is Dest.Title -> TvTitle(top.ref, onPlay = { s, e -> nav.open(Dest.Sources(top.ref, s, e)) })
            is Dest.Sources -> SourcesScreen(top.ref, top.season, top.episode, isTv = true, onBack = { nav.back() })
            null -> {
                when (nav.tab) {
                    Tab.Home -> TvHome(onOpen = { nav.open(Dest.Title(it.ref)) })
                    Tab.Search -> TvSearch(onOpen = { nav.open(Dest.Title(it.ref)) })
                    Tab.Downloads -> Box(Modifier.padding(top = TvTopBarHeight)) {
                        EngineScreen(isTv = true, incomingMagnet = incomingMagnet, onIncomingHandled = onIncomingHandled)
                    }
                    Tab.Settings -> Box(Modifier.padding(top = TvTopBarHeight)) { SettingsScreen(isTv = true) }
                }
                TvTopBar(nav)
            }
        }
    }
}

@Composable
private fun TvTopBar(nav: Navigator) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        Text("Reel", style = Tokens.Type.title, color = Tokens.Colors.accent)
        Spacer(Modifier.width(Tokens.Space.l))
        Tab.entries.forEach { tab ->
            ReelButton(tab.label, { nav.select(tab) }, kind = if (nav.tab == tab) ButtonKind.Primary else ButtonKind.Ghost)
        }
    }
}
