package com.kirtanya.reel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True under the TV root (TvApp): sheets become a side panel driven by the remote. */
val LocalIsTv = staticCompositionLocalOf { false }

/** One row of an options sheet. */
data class OptionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    /** Shown under the label ("S2 E7", "From 0:00"). */
    val detail: String? = null,
    /** Removes something (drawn in the error colour). */
    val danger: Boolean = false,
)

/** What sits on top of the sheet: the title's art, its name and a quiet line under it. */
data class OptionsHeader(
    val title: String,
    val subtitle: String? = null,
    val art: String? = null,
    /** Posters are 2:3; stills and backdrops 16:9. */
    val poster: Boolean = false,
)

/**
 * Long-press options for a card (hold OK on the remote). Phone: a bottom sheet with the title art on
 * top. TV: a panel on the right; focus stays inside it and Back closes it. Picking a row closes the
 * sheet, then runs the action.
 */
@Composable
fun OptionsSheet(header: OptionsHeader, actions: List<OptionAction>, onDismiss: () -> Unit, isTv: Boolean = LocalIsTv.current) {
    if (isTv) OptionsPanelTv(header, actions, onDismiss) else OptionsSheetPhone(header, actions, onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheetPhone(header: OptionsHeader, actions: List<OptionAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Colors.surface1,
        shape = RoundedCornerShape(topStart = Tokens.Radius.xl, topEnd = Tokens.Radius.xl),
        dragHandle = { Box(Modifier.padding(top = Tokens.Space.m).size(width = 32.dp, height = Tokens.Size.progressBar).clip(RoundedCornerShape(Tokens.Radius.pill)).background(Tokens.Colors.hairlineStrong)) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = Tokens.Space.s, end = Tokens.Space.s, top = Tokens.Space.l, bottom = Tokens.Space.l),
        ) {
            HeaderView(header, artWidth = if (header.poster) 64.dp else 128.dp, Modifier.padding(horizontal = Tokens.Space.s))
            Box(Modifier.padding(vertical = Tokens.Space.m, horizontal = Tokens.Space.s).fillMaxWidth().height(Tokens.Size.hairline).background(Tokens.Colors.hairline))
            actions.forEach { a -> OptionRow(a, rowHeight = Tokens.Size.touchTarget + Tokens.Space.s) { onDismiss(); a.onClick() } }
        }
    }
}

@Composable
private fun OptionsPanelTv(header: OptionsHeader, actions: List<OptionAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // A window of its own: the remote can't leave it, and Back dismisses it.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Tokens.Colors.scrim)) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(TV_PANEL_WIDTH)
                    .background(Tokens.Colors.surface1)
                    .border(Tokens.Size.hairline, Tokens.Colors.hairline)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Tokens.Space.xl, vertical = Tokens.Space.tvSafeV),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
            ) {
                HeaderView(header, artWidth = if (header.poster) 72.dp else 136.dp, Modifier.padding(bottom = Tokens.Space.m))
                actions.forEachIndexed { i, a ->
                    OptionRow(a, rowHeight = Tokens.Size.buttonLarge, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onDismiss(); a.onClick() }
                }
            }
        }
        // Inside the dialog's window: the requester only exists once that window is composed.
        LaunchedEffect(Unit) { takeFocus(first) }
    }
}

/**
 * Focus [target] and keep it for a moment: a window closing (the options panel) or a screen's own first
 * focus can hand focus back to the card a frame later.
 */
private suspend fun takeFocus(target: FocusRequester) {
    repeat(3) {
        runCatching { target.requestFocus() }
        delay(Tokens.Motion.focusMs.toLong())
    }
}

private val TV_PANEL_WIDTH = 360.dp

@Composable
private fun HeaderView(header: OptionsHeader, artWidth: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Tokens.Radius.media)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(artWidth).aspectRatio(if (header.poster) 2f / 3f else 16f / 9f).clip(shape)
                .background(Tokens.Colors.tonal).border(Tokens.Size.hairline, Tokens.Colors.hairline, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (header.art != null) {
                RemoteImage(header.art, Modifier.fillMaxSize())
            } else {
                Text(header.title.trim().take(1).uppercase(), style = Tokens.Type.title, color = Tokens.Colors.onTonal)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text(header.title, style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            header.subtitle?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun OptionRow(a: OptionAction, rowHeight: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (a.danger) Tokens.Colors.danger else Tokens.Colors.text
    Row(
        modifier.fillMaxWidth().heightIn(min = rowHeight)
            .focusCard(shape = RoundedCornerShape(Tokens.Radius.m), onClick = onClick)
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(a.icon, null, tint = if (a.danger) Tokens.Colors.danger else Tokens.Colors.textSecondary, modifier = Modifier.size(Tokens.Size.barIcon))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text(a.label, style = Tokens.Type.label, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            a.detail?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ─── Undo ──────────────────────────────────────────────────────────────────────────────────

/**
 * Removals the user can take back: the item is hidden at once, a snackbar offers Undo, and the real
 * removal runs when the snackbar goes away. Lives at the app root, so leaving the screen doesn't
 * lose it; if the app is torn down first, the removal still runs.
 */
class UndoHost(val snackbar: SnackbarHostState, private val scope: CoroutineScope) {
    /** Keys hidden while their snackbar is up (Continue watching filters on these). */
    val hidden = mutableStateListOf<String>()

    fun remove(key: String, message: String, commit: () -> Unit, refocus: (() -> Unit)? = null) {
        hidden += key
        scope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
            } finally {
                if (!undone) commit()
                hidden -= key
                refocus?.let { runCatching { it() } }
            }
        }
    }
}

val LocalUndo = staticCompositionLocalOf<UndoHost?> { null }

@Composable
fun rememberUndoHost(): UndoHost {
    val scope = rememberCoroutineScope()
    return remember { UndoHost(SnackbarHostState(), scope) }
}

/**
 * The snackbar itself: surfaceHighest card, message and an Undo button. On TV the button takes focus
 * (it's the only way to reach it); the screen gets focus back when it goes.
 */
@Composable
fun UndoSnackbarHost(host: UndoHost, isTv: Boolean, modifier: Modifier = Modifier) {
    SnackbarHost(host.snackbar, modifier) { data ->
        val undo = remember(data) { FocusRequester() }
        val shape = RoundedCornerShape(Tokens.Radius.l)
        Row(
            Modifier.then(if (isTv) Modifier.width(TV_PANEL_WIDTH) else Modifier.fillMaxWidth())
                .clip(shape).background(Tokens.Colors.surface3).border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
                .padding(start = Tokens.Space.l, end = Tokens.Space.s, top = Tokens.Space.xs, bottom = Tokens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            Text(data.visuals.message, Modifier.weight(1f), style = Tokens.Type.body, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            data.visuals.actionLabel?.let { label ->
                ReelButton(label, { data.performAction() }, Modifier.focusRequester(undo), kind = ButtonKind.Ghost)
            }
        }
        if (isTv) LaunchedEffect(data) { takeFocus(undo) }
    }
}
