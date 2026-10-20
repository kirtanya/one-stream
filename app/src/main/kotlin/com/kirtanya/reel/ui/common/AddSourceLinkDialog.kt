package com.kirtanya.reel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/** Outcome of tapping Add. */
private sealed interface AddOutcome {
    data class Added(val message: String) : AddOutcome
    data class Failed(val message: String) : AddOutcome
}

/**
 * "Add this addon?" after an Install button / stremio:// link or a shared manifest link.
 * Shows what the link is (fetched once) and installs exactly that, so nothing is added by surprise.
 * Call it inside `key(link)` so a second link gets fresh state.
 */
@Composable
fun AddSourceLinkDialog(link: String, isTv: Boolean, onDone: () -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val fixed = LocalRenderData.current?.linkPreview
    val (livePreview, retry) = rememberLoad(link) { if (fixed != null) kotlinx.coroutines.awaitCancellation() else app.sources.preview(link) }
    val preview = fixed ?: livePreview
    var adding by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<AddOutcome?>(null) }
    val buttonFocus = remember { FocusRequester() }
    // On TV keep the remote on the main button as the dialog changes state (loading → Add → OK / Try again).
    if (isTv) RequestFocusOnce(buttonFocus, key = Triple(preview::class, outcome, adding))

    AlertDialog(
        onDismissRequest = { if (!adding) onDone() },
        // While adding, the dialog stays until it finishes (a half-added plugin repo helps no one).
        properties = DialogProperties(dismissOnBackPress = !adding, dismissOnClickOutside = !adding),
        containerColor = Tokens.Colors.surface1,
        title = {
            Text(
                when (val o = outcome) {
                    is AddOutcome.Added -> "Added"
                    is AddOutcome.Failed -> "Couldn't add it"
                    null -> (preview as? Load.Ready)?.let { "Add ${it.value.name}?" } ?: "Add this link?"
                },
                style = Tokens.Type.title,
                color = Tokens.Colors.text,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                when (val o = outcome) {
                    is AddOutcome.Added -> Text(o.message, style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                    is AddOutcome.Failed -> Text(o.message, style = Tokens.Type.body, color = Tokens.Colors.warn)
                    null -> when (preview) {
                        Load.Loading -> CircularProgressIndicator(color = Tokens.Colors.accent)
                        is Load.Failed -> Text(preview.message, style = Tokens.Type.body, color = Tokens.Colors.warn)
                        is Load.Ready -> {
                            // First line says what it is; the addon's own description goes underneath.
                            val (kind, description) = (preview.value.detail ?: "").split("\n", limit = 2).let { it[0] to it.getOrNull(1) }
                            if (kind.isNotBlank()) Text(kind, style = Tokens.Type.label, color = Tokens.Colors.text)
                            description?.takeIf { it.isNotBlank() }?.let { Text(it, style = Tokens.Type.body, color = Tokens.Colors.textSecondary) }
                            Text(preview.value.url, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 2)
                            Text(
                                "You choose what to add. Streaming or downloading content you don't have rights to is illegal in most countries.",
                                style = Tokens.Type.caption,
                                color = Tokens.Colors.textTertiary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val focus = Modifier.focusRequester(buttonFocus)
            when {
                outcome is AddOutcome.Added -> ReelButton("OK", onDone, focus, kind = ButtonKind.Primary)
                outcome is AddOutcome.Failed || preview is Load.Failed -> ReelButton("Try again", {
                    outcome = null
                    retry()
                }, focus, kind = ButtonKind.Secondary)
                else -> ReelButton(
                    if (adding) "Adding…" else "Add",
                    {
                        val ready = preview as? Load.Ready ?: return@ReelButton
                        if (adding) return@ReelButton
                        adding = true
                        app.appScope.launch {
                            outcome = try {
                                AddOutcome.Added(app.sources.addPreviewed(ready.value))
                            } catch (e: SourceSetupException) {
                                AddOutcome.Failed(e.message ?: "Couldn't add it")
                            } catch (e: Exception) {
                                AddOutcome.Failed("Couldn't add it: ${e.message}")
                            }
                            adding = false
                        }
                    },
                    focus,
                    kind = ButtonKind.Primary,
                    icon = Icons.Rounded.Add,
                    enabled = preview is Load.Ready,
                )
            }
        },
        dismissButton = { if (outcome == null && !adding) ReelButton("Cancel", onDone, kind = ButtonKind.Ghost) },
    )
}
