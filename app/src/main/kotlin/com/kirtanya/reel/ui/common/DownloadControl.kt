package com.kirtanya.reel.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.library.DlAction
import com.kirtanya.reel.core.library.DlKind
import com.kirtanya.reel.core.library.DownloadButtonState
import com.kirtanya.reel.core.library.RowMark
import com.kirtanya.reel.core.library.TitleDownload
import com.kirtanya.reel.ui.theme.Tokens

/**
 * The download button of a title page (docs/DESIGN_STYLE.md "Download status on the title page"): one control for every
 * state, drawn from a [DownloadButtonState]. Idle it is just a "Download" button. Once something exists it grows a quiet
 * surface with the state in words, a thin progress line and the actions that make sense now.
 *
 * Buttons keep fixed places so the focus never lands on something else when the state changes under it:
 * the lifecycle button (Download, Starting…, Pause, Resume, Try again) first, Play second, Change source third, and the
 * delete icon last. If the focused button goes away (a download finishes, Pause becomes nothing), focus moves to the first
 * button that is left, never to the page behind. Nothing here animates: the line grows a whole percent at a time.
 *
 * [playHere]: this control draws Play itself (the TV film page). The phone's page has its own Play button and passes false.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadControl(
    state: DownloadButtonState,
    deleteName: String,
    onAction: (DlAction) -> Unit,
    modifier: Modifier = Modifier,
    playHere: Boolean = false,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val idle = state.kind == DlKind.IDLE
    val shape = RoundedCornerShape(Tokens.Radius.l)

    // Which button should have focus if the one that has it disappears: the first one drawn (see the class comment).
    val first = remember { FocusRequester() }
    val focusedNow = remember { BooleanArray(1) }
    val seenKind = remember { arrayOf(state.kind) }
    val keepFocus = remember { BooleanArray(1) }
    if (seenKind[0] != state.kind) {
        // Still the composition that is about to remove the old button: the focus flag is true if it was on this control.
        keepFocus[0] = focusedNow[0]
        seenKind[0] = state.kind
    }
    if (keepFocus[0]) {
        LaunchedEffect(state.kind) {
            keepFocus[0] = false
            withFrameNanos { }
            if (!focusedNow[0]) runCatching { first.requestFocus() }
        }
    }

    Column(
        modifier
            .onFocusChanged { focusedNow[0] = it.hasFocus }
            .focusGroup()
            .then(if (idle) Modifier else Modifier.fillMaxWidth().background(Tokens.Colors.surface1, shape).border(Tokens.Size.hairline, Tokens.Colors.hairline, shape).padding(Tokens.Space.m)),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        if (!idle) Header(state)
        state.bar?.let { GradientProgress(it, height = 3.dp) }

        val lifecycle = state.actions.firstOrNull { it == DlAction.DOWNLOAD || it == DlAction.PAUSE || it == DlAction.RESUME || it == DlAction.RETRY }
        val play = DlAction.PLAY.takeIf { playHere && it in state.actions }
        val change = DlAction.CHANGE_SOURCE.takeIf { it in state.actions }
        val delete = DlAction.DELETE.takeIf { it in state.actions }
        // "Starting…" has no action yet: its button is there so a second press is swallowed and focus has somewhere to be.
        val starting = state.kind == DlKind.STARTING && lifecycle == null
        var firstUsed = false
        fun Modifier.firstOnce(): Modifier = if (firstUsed) this else { firstUsed = true; focusRequester(first) }

        if (lifecycle != null || starting || play != null || change != null || delete != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
                if (starting) ReelButton("Starting…", {}, Modifier.firstOnce(), kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
                if (lifecycle != null) {
                    ReelButton(labelOf(lifecycle, state), { onAction(lifecycle) }, Modifier.firstOnce(), kind = ButtonKind.Secondary, icon = iconOf(lifecycle))
                }
                if (play != null) ReelButton(state.playLabel ?: "Play", { onAction(play) }, Modifier.firstOnce(), kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow)
                if (change != null) ReelButton("Change source", { onAction(change) }, Modifier.firstOnce(), kind = ButtonKind.Ghost, icon = Icons.Rounded.SwapHoriz)
                if (delete != null) GlassIconButton(Icons.Rounded.Delete, "Delete download", { confirmDelete = true }, Modifier.firstOnce())
            }
        }
    }
    if (confirmDelete) DeleteDownloadDialog(deleteName, onConfirm = { confirmDelete = false; onAction(DlAction.DELETE) }, onDismiss = { confirmDelete = false })
}

private fun labelOf(a: DlAction, s: DownloadButtonState) = when (a) {
    DlAction.DOWNLOAD -> if (s.kind == DlKind.FAILED) "Try again" else "Download"
    DlAction.PAUSE -> "Pause"
    DlAction.RESUME -> "Resume"
    DlAction.RETRY -> "Try again"
    DlAction.PLAY -> s.playLabel ?: "Play"
    DlAction.CHANGE_SOURCE -> "Change source"
    DlAction.DELETE -> "Delete"
}

private fun iconOf(a: DlAction): ImageVector = when (a) {
    DlAction.PAUSE -> Icons.Rounded.Pause
    DlAction.RETRY -> Icons.Rounded.Refresh
    else -> Icons.Rounded.Download
}

@Composable
private fun Header(state: DownloadButtonState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
        val (icon, tint) = when (state.kind) {
            DlKind.DONE -> Icons.Rounded.DownloadDone to Tokens.Colors.good
            DlKind.FAILED, DlKind.NOT_READY -> Icons.Rounded.ErrorOutline to Tokens.Colors.warn
            DlKind.PAUSED -> Icons.Rounded.Pause to Tokens.Colors.textSecondary
            DlKind.WAITING -> Icons.Rounded.Schedule to Tokens.Colors.textSecondary
            DlKind.READY -> Icons.Rounded.PlayArrow to Tokens.Colors.accentBright
            else -> Icons.Rounded.Download to Tokens.Colors.accentBright
        }
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text(state.label, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            state.detail?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Same words and buttons as the Downloads screen's delete. */
@Composable
fun DeleteDownloadDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface1,
        title = { Text("Delete “$name”?", style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
        text = { Text("This removes the download and its files from this device.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary) },
        confirmButton = { ReelButton("Delete", onConfirm, kind = ButtonKind.Primary, icon = Icons.Rounded.Delete) },
        dismissButton = { ReelButton("Keep it", onDismiss, kind = ButtonKind.Ghost) },
    )
}

/**
 * The small status mark on an episode's picture. Finished = the green disc everyone knows ([DownloadedBadge]); playable while it
 * downloads = a solid accent disc with a play mark; running = a thin ring that fills; the rest are a glass disc with one glyph
 * (paused, waiting, problem). Static: it changes when a whole percent changes, never on its own.
 */
@Composable
fun EpisodeDownloadMark(state: DownloadButtonState, modifier: Modifier = Modifier) {
    val mark = TitleDownload.mark(state.kind)
    if (mark == RowMark.NONE) return
    if (mark == RowMark.DONE) {
        DownloadedBadge(modifier)
        return
    }
    val tv = LocalIsTv.current
    val size = if (tv) 28.dp else 22.dp
    val glyph = if (tv) 16.dp else 12.dp
    val what = state.label + (state.detail?.let { ", $it" } ?: "")
    val ring = Tokens.Colors.accentBright
    val track = Tokens.Colors.hairlineStrong
    Box(
        modifier.size(size).semantics { contentDescription = what }
            .background(if (mark == RowMark.READY) Tokens.Colors.accent else Tokens.Colors.glass, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (mark) {
            RowMark.READY -> Icon(Icons.Rounded.PlayArrow, null, tint = Tokens.Colors.onAccent, modifier = Modifier.size(glyph + 2.dp))
            RowMark.PROGRESS -> {
                val share = state.bar ?: 0f
                Canvas(Modifier.size(size)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2 + 1.dp.toPx()
                    val arc = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(ring, -90f, 360f * share.coerceIn(0.04f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            RowMark.PAUSED -> Icon(Icons.Rounded.Pause, null, tint = Tokens.Colors.text, modifier = Modifier.size(glyph))
            RowMark.WAITING -> Icon(Icons.Rounded.Schedule, null, tint = Tokens.Colors.text, modifier = Modifier.size(glyph))
            RowMark.PROBLEM -> Icon(Icons.Rounded.ErrorOutline, null, tint = Tokens.Colors.warn, modifier = Modifier.size(glyph + 2.dp))
            else -> Icon(Icons.Rounded.Download, null, tint = Tokens.Colors.text, modifier = Modifier.size(glyph))
        }
    }
}
