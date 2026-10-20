package com.kirtanya.reel.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.sync.SyncProtocol
import com.kirtanya.reel.data.sync.FoundTv
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** "Phone & TV" card content: the TV shows a pairing code while on screen; the phone pairs and keeps TVs in sync. */
@Composable
fun ColumnScope.SyncContent(isTv: Boolean) {
    if (isTv) TvSyncContent() else PhoneSyncContent()
}

@Composable
private fun TvSyncContent() {
    val app = ReelApp.get(LocalContext.current)
    val state by app.tvSync.state.collectAsState()
    // Pairing is only possible while this card is on screen (and for a few minutes at most).
    DisposableEffect(Unit) {
        app.tvSync.openPairing()
        onDispose { app.tvSync.closePairing() }
    }
    if (!state.running) {
        Text("Sync isn't available: the TV couldn't open its sync port.", style = Tokens.Type.body, color = Tokens.Colors.warn)
        return
    }
    Text(
        "Set up addons and plugins on your phone and they'll appear here. On the phone: One Stream → Settings → Phone & TV → Find TVs, then enter this code.",
        style = Tokens.Type.body,
        color = Tokens.Colors.textSecondary,
    )
    if (state.code.isNotEmpty()) {
        var left by remember(state.code) { mutableLongStateOf(SyncProtocol.PAIRING_WINDOW_MS / 1000) }
        LaunchedEffect(state.code) {
            while (left > 0) {
                delay(1_000)
                left--
            }
            app.tvSync.closePairing()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            Column {
                Text("Pairing code", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                Text(state.code.chunked(3).joinToString(" "), style = Tokens.Type.hero.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Tokens.Colors.accentBright)
                Text("Valid for ${left / 60}:${"%02d".format(left % 60)}", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
            }
            Pill(state.deviceName)
        }
    } else {
        ReelButton("Show a pairing code", { app.tvSync.openPairing() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
    }
    state.lastUpdate?.let { Text("Last update from $it · ${ago(state.lastUpdateAt)}", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary) }
    state.pairedPhones.forEach { phone ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Text(phone.name, style = Tokens.Type.label, color = Tokens.Colors.text, modifier = Modifier.weight(1f))
            Pill("Paired", accent = true)
            ForgetButton(phone.name) { app.tvSync.forgetPhone(phone.id) }
        }
    }
}

@Composable
private fun PhoneSyncContent() {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val state by app.phoneSync.state.collectAsState()
    // Work runs in the app's scope, so leaving Settings doesn't cancel a pairing or a sync halfway.
    val scope = app.appScope
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundTv>?>(null) }
    var pairing by remember { mutableStateOf<FoundTv?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun search() {
        searching = true
        note = null
        scope.launch {
            val tvs = runCatching { app.phoneSync.discover() }.getOrDefault(emptyList())
            found = tvs
            searching = false
        }
    }
    // Android 17+ asks before an app looks around the home network. If refused we still try:
    // depending on the app's target, the search may work anyway.
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        search()
    }
    fun findTvs() {
        val needsPermission = Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && !permissionDenied) askLocalNetwork.launch(LOCAL_NETWORK) else search()
    }

    Text(
        "Send your addons and plugins to your TV, so you only set them up once. Changes you make here reach the TV by themselves.",
        style = Tokens.Type.body,
        color = Tokens.Colors.textSecondary,
    )
    state.tvs.forEach { tv ->
        // Name and status on their own line; actions underneath (fits a 375 dp phone).
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Text(tv.name, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    tv.lastError != null -> tv.lastError
                    tv.lastSyncAt > 0 -> "Synced ${ago(tv.lastSyncAt)} · ${tv.lastSummary}"
                    else -> "Paired"
                },
                style = Tokens.Type.caption,
                color = if (tv.lastError != null) Tokens.Colors.warn else Tokens.Colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (tv.lastError == null && tv.lastSyncAt > 0) Pill("In sync", accent = true)
                if (tv.needsPairing) {
                    ReelButton("Pair again", { findTvs() }, kind = ButtonKind.Primary, icon = Icons.Rounded.Search)
                } else {
                    ReelButton(
                        if (state.syncing) "Syncing…" else "Sync now",
                        { scope.launch { note = app.phoneSync.push(tv.tvId, force = true) } },
                        kind = ButtonKind.Secondary,
                        icon = Icons.Rounded.Sync,
                        enabled = !state.syncing,
                    )
                }
                ForgetButton(tv.name) { app.phoneSync.forget(tv.tvId) }
            }
        }
    }
    if (state.tvs.isNotEmpty()) {
        ToggleRow("Keep TVs in sync automatically", "Sends your sources a few seconds after you change them.", state.autoSync) {
            app.phoneSync.setAutoSync(!state.autoSync)
        }
    }

    val pairWith = pairing
    if (pairWith != null) {
        Text("Enter the code shown on ${pairWith.name} (Settings → Phone & TV).", style = Tokens.Type.label, color = Tokens.Colors.text)
        AddField(
            code,
            { code = it.filter(Char::isDigit).take(SyncProtocol.CODE_LENGTH) },
            "Pair",
            busy,
            placeholder = "${SyncProtocol.CODE_LENGTH}-digit code",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            ready = code.length == SyncProtocol.CODE_LENGTH,
        ) {
            busy = true
            scope.launch {
                runCatching { app.phoneSync.pair(pairWith, code) }
                    .onSuccess { msg ->
                        note = msg
                        pairing = null
                        found = null
                    }
                    // Keep the field so a mistyped code can be fixed.
                    .onFailure { note = it.message }
                code = ""
                busy = false
            }
        }
        ReelButton("Cancel", { pairing = null; code = ""; note = null }, kind = ButtonKind.Ghost)
    } else {
        found?.let { tvs ->
            if (tvs.isEmpty()) {
                Text(
                    "No TV found. Open One Stream on the TV (on the same Wi-Fi as this phone), then search again.",
                    style = Tokens.Type.caption,
                    color = Tokens.Colors.textTertiary,
                )
                if (permissionDenied) {
                    ReelButton("Allow local network access", {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }, kind = ButtonKind.Secondary)
                }
            }
            tvs.forEach { tv ->
                val known = state.tvs.any { it.tvId == tv.tvId }
                ReelButton(if (known) "Pair again with ${tv.name}" else "Pair with ${tv.name}", { pairing = tv; note = null }, kind = ButtonKind.Secondary)
            }
        }
        ReelButton(
            if (searching) "Looking for TVs…" else "Find TVs",
            ::findTvs,
            kind = if (state.tvs.isEmpty()) ButtonKind.Primary else ButtonKind.Secondary,
            icon = Icons.Rounded.Search,
            enabled = !searching,
        )
    }
    note?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
}

/**
 * Forget needs a second press: on the TV it can be the only focusable thing in the card,
 * so one stray OK on the remote mustn't unpair.
 */
@Composable
private fun ForgetButton(name: String, onForget: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    if (!armed) {
        GlassIconButton(Icons.Rounded.Delete, "Forget $name", { armed = true })
    } else {
        ReelButton("Forget $name", onForget, kind = ButtonKind.Secondary, icon = Icons.Rounded.Delete)
        ReelButton("Keep", { armed = false }, kind = ButtonKind.Ghost)
    }
}

private fun ago(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} d ago"
    }
}
