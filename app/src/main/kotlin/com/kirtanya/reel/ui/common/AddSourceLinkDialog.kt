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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * "Add this addon?" after an Install button / stremio:// link or a shared manifest link.
 * Shows what the link is (fetched first) so nothing is added by surprise.
 */
@Composable
fun AddSourceLinkDialog(link: String, isTv: Boolean, onDone: () -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    val (preview, retry) = rememberLoad(link) { app.sources.preview(link) }
    var adding by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val addFocus = remember { FocusRequester() }
    if (isTv && preview is Load.Ready && result == null) RequestFocusOnce(addFocus, key = link)

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Tokens.Colors.surface1,
        title = {
            Text(
                when {
                    result != null -> "Done"
                    preview is Load.Ready -> "Add ${preview.value.name}?"
                    else -> "Add this link?"
                },
                style = Tokens.Type.title,
                color = Tokens.Colors.text,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                when {
                    result != null -> Text(result!!, style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                    preview is Load.Loading -> CircularProgressIndicator(color = Tokens.Colors.accent)
                    preview is Load.Failed -> Text(preview.message, style = Tokens.Type.body, color = Tokens.Colors.warn)
                    preview is Load.Ready -> {
                        preview.value.detail?.let { Text(it, style = Tokens.Type.body, color = Tokens.Colors.textSecondary) }
                        Text(preview.value.url, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 2)
                        Text(
                            "You choose what to add. Streaming or downloading content you don't have rights to is illegal in most countries.",
                            style = Tokens.Type.caption,
                            color = Tokens.Colors.textTertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                result != null -> ReelButton("OK", onDone, kind = ButtonKind.Primary)
                preview is Load.Failed -> ReelButton("Try again", { retry() }, kind = ButtonKind.Secondary)
                else -> ReelButton(
                    if (adding) "Adding…" else "Add",
                    {
                        adding = true
                        scope.launch {
                            result = try {
                                app.sources.addFromLink(link)
                            } catch (e: SourceSetupException) {
                                e.message
                            } catch (e: Exception) {
                                "Couldn't add it: ${e.message}"
                            }
                            adding = false
                        }
                    },
                    modifier = Modifier.focusRequester(addFocus),
                    kind = ButtonKind.Primary,
                    icon = Icons.Rounded.Add,
                    enabled = preview is Load.Ready && !adding,
                )
            }
        },
        dismissButton = { if (result == null) ReelButton("Cancel", onDone, kind = ButtonKind.Ghost) },
    )
}
