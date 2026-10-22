package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tvremote.RemoteKeys
import com.kirtanya.reel.tvremote.RemoteTv
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Phone only: pair One Stream as the TV's remote (the TV's own remote protocol, like Google Home),
 * so "Send to TV" can turn the TV on from standby and start the video there.
 */
@Composable
fun ColumnScope.TvRemoteContent() {
    val app = ReelApp.get(LocalContext.current)
    val remote = app.tvRemote
    val paired by remote.pairedFlow.collectAsState()
    val scope = app.appScope
    var found by remember { mutableStateOf<List<RemoteTv>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<RemoteTv?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val tv = paired
    if (tv != null) {
        Text("Paired with ${tv.name}. “Send to TV” turns it on if it's asleep, then plays there.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            ReelButton("Turn TV on", {
                busy = true
                scope.launch {
                    note = if (remote.connect() && remote.key(RemoteKeys.WAKEUP)) "Sent “turn on” to ${tv.name}" else "Couldn't reach ${tv.name}"
                    busy = false
                }
            }, kind = ButtonKind.Secondary, icon = Icons.Rounded.PowerSettingsNew, enabled = !busy)
            ReelButton("Forget", { remote.forget(); note = null }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
        }
    } else {
        Text(
            "Let One Stream act as your TV's remote, so “Send to TV” can wake the TV from standby and start the video.",
            style = Tokens.Type.body, color = Tokens.Colors.textSecondary,
        )
        val pairWith = pairing
        if (pairWith != null) {
            Text("Enter the code shown on ${pairWith.name}.", style = Tokens.Type.label, color = Tokens.Colors.text)
            AddField(
                code, { code = it.filter { c -> c.isLetterOrDigit() }.uppercase().take(6) }, "Pair", busy,
                placeholder = "6-character code",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
                ready = code.length == 6,
            ) {
                busy = true
                scope.launch {
                    runCatching { remote.pairFinish(code) }
                        .onSuccess { pairing = null; found = null; note = "Paired with ${pairWith.name}" }
                        .onFailure { note = it.message ?: "Pairing failed" }
                    code = ""
                    busy = false
                }
            }
            ReelButton("Cancel", { pairing = null; code = ""; note = null }, kind = ButtonKind.Ghost)
        } else {
            found?.let { tvs ->
                if (tvs.isEmpty()) Text("No TV found. Is the TV on and on this Wi-Fi?", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                tvs.forEach { t ->
                    ReelButton("Pair with ${t.name}", {
                        busy = true
                        note = null
                        scope.launch {
                            runCatching { remote.pairStart(t) }
                                .onSuccess { pairing = t }
                                .onFailure { note = "Couldn't start pairing: ${it.message}" }
                            busy = false
                        }
                    }, kind = ButtonKind.Secondary, enabled = !busy)
                }
            }
            ReelButton(if (searching) "Looking for TVs…" else "Find TV", {
                searching = true
                scope.launch {
                    found = runCatching { remote.discover() }.getOrDefault(emptyList())
                    searching = false
                }
            }, kind = ButtonKind.Primary, icon = Icons.Rounded.Search, enabled = !searching && !busy)
        }
    }
    note?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
}
