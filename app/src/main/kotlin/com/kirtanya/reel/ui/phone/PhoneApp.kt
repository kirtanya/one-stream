package com.kirtanya.reel.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.kirtanya.reel.ui.EngineScreen
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.nav.rememberNavigator
import com.kirtanya.reel.ui.theme.Tokens

/** Phone root: Home · Search · Downloads tabs, title pages stack on top. */
@Composable
fun PhoneApp(incomingMagnet: String?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavigator()
    val saveable = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.Home) { nav.back() }

    Column(Modifier.fillMaxSize().background(Tokens.Colors.bg)) {
        Box(Modifier.weight(1f)) {
            when (val top = nav.top) {
                is Dest.Title -> saveable.SaveableStateProvider(top.ref.toString()) {
                    PhoneTitle(top.ref, onBack = { nav.back() })
                }
                null -> saveable.SaveableStateProvider(nav.tab.name) {
                    when (nav.tab) {
                        Tab.Home -> PhoneHome(onOpen = { nav.open(Dest.Title(it.ref)) })
                        Tab.Search -> PhoneSearch(onOpen = { nav.open(Dest.Title(it.ref)) })
                        Tab.Downloads -> EngineScreen(isTv = false, incomingMagnet = null, onIncomingHandled = {})
                    }
                }
            }
        }
        NavigationBar(containerColor = Tokens.Colors.surface) {
            listOf(Tab.Home to Icons.Rounded.Home, Tab.Search to Icons.Rounded.Search, Tab.Downloads to Icons.Rounded.Download)
                .forEach { (tab, icon) ->
                    NavigationBarItem(
                        selected = nav.tab == tab && nav.stack.isEmpty(),
                        onClick = { nav.select(tab) },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(tab.label, style = Tokens.Type.caption) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Tokens.Colors.onAccent,
                            indicatorColor = Tokens.Colors.accent,
                            selectedTextColor = Tokens.Colors.text,
                            unselectedIconColor = Tokens.Colors.textMuted,
                            unselectedTextColor = Tokens.Colors.textMuted,
                        ),
                    )
                }
        }
    }

    // A magnet opened from a browser / shared in: handled on the Downloads screen's dialog.
    if (incomingMagnet != null) {
        EngineScreenDialogHost(incomingMagnet, onIncomingHandled)
    }
}

@Composable
private fun EngineScreenDialogHost(magnet: String, onDone: () -> Unit) {
    // Reuse the engine screen's Stream / Download dialog without showing its list.
    Box(Modifier.fillMaxSize()) { EngineScreen(isTv = false, incomingMagnet = magnet, onIncomingHandled = onDone, dialogOnly = true) }
}
