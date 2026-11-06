package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ui.theme.Tokens

/** The panel's width: 40 % of the TV's 960 dp. */
val TvSettingsPanelWidth = 384.dp

/**
 * Settings on the TV: a panel over the right of the current screen (the launcher-settings-sheet look), a solid
 * surface with a hairline edge (no blur, no scrim: the screen stays visible, so a change like the card style shows
 * live). The remote can't leave it ([FocusRequester.Cancel] on every exit), Back closes a sub-page, then the panel
 * ([SettingsScreen]'s `onClose`). The host puts focus back on the rail's Settings icon once it's gone.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun TvSettingsPanel(onClose: () -> Unit, modifier: Modifier = Modifier, start: List<SettingsPage> = emptyList()) {
    Box(
        modifier
            .fillMaxHeight()
            .width(TvSettingsPanelWidth)
            .background(Tokens.Colors.canvas)
            // The edge: a 1 dp line, drawn as a thin box so it's one rect rather than a stroked path.
            .focusProperties {
                exit = { dir ->
                    when (dir) {
                        FocusDirection.Left, FocusDirection.Right, FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
                        else -> FocusRequester.Default
                    }
                }
            }
            .focusGroup(),
    ) {
        Box(Modifier.fillMaxHeight().width(Tokens.Size.hairline).background(Tokens.Colors.hairlineStrong))
        SettingsScreen(isTv = true, start = start, onClose = onClose)
    }
}
