package com.kirtanya.reel.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.data.sync.FoundTv
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** "Phone & TV" card content: the TV shows its pairing code; the phone pairs and keeps TVs in sync. */
@Composable
fun ColumnScope.SyncContent(isTv: Boolean, onMessage: (String?) -> Unit) {
    if (isTv) TvSyncContent() else PhoneSyncContent(onMessage)
}

@Composable
private fun TvSyncContent() {
    val app = ReelApp.get(LocalContext.current)
    val state by app.tvSync.state.collectAsState()
    if (!state.running) {
        Text("Sync isn't available: the TV couldn't open its sync port.", style = Tokens.Type.body, color = Tokens.Colors.warn)
        return
    }
    Text(
        "Set up addons and plugins on your phone, and they'll appear here. On the phone: Reel → Settings → Phone & TV → Find TVs, then enter this code.",
        style = Tokens.Type.body,
        color = Tokens.Colors.textSecondary,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
        Column {
            Text("Pairing code", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
            Text(state.code, style = Tokens.Type.hero, color = Tokens.Colors.accentBright)
        }
        Pill(state.deviceName)
    }
    state.lastUpdate?.let { Text("Last update from $it · ${ago(state.lastUpdateAt)}", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary) }
    state.pairedPhones.forEach { (token, phone) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Text(phone, style = Tokens.Type.label, color = Tokens.Colors.text, modifier = Modifier.weight(1f))
            Pill("Paired", accent = true)
            GlassIconButton(Icons.Rounded.Delete, "Forget $phone", { app.tvSync.forgetPhone(token) })
        }
    }
}

@Composable
private fun PhoneSyncContent(onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val state by app.phoneSync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundTv>?>(null) }
    var pairing by remember { mutableStateOf<FoundTv?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun search() {
        searching = true
        scope.launch {
            found = runCatching { app.phoneSync.discover() }.getOrDefault(emptyList())
                .filterNot { tv -> state.tvs.any { it.serviceName == tv.serviceName } }
            searching = false
        }
    }
    // Android 17+: finding devices on the home network needs the "local network" permission.
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) search() else onMessage("Reel needs local network access to find your TV")
    }
    fun findTvs() {
        val needsPermission = Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) askLocalNetwork.launch(LOCAL_NETWORK) else search()
    }

    Text(
        "Send your addons and plugins to your TV, so you only set them up once. Changes you make here reach the TV by themselves.",
        style = Tokens.Type.body,
        color = Tokens.Colors.textSecondary,
    )
    state.tvs.forEach { tv ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Column(Modifier.weight(1f)) {
                Text(tv.name, style = Tokens.Type.label, color = Tokens.Colors.text)
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
            }
            if (tv.lastError == null && tv.lastSyncAt > 0) Pill("In sync", accent = true)
            ReelButton(
                if (state.syncing) "Syncing…" else "Sync now",
                { scope.launch { onMessage(app.phoneSync.push(tv.serviceName, force = true)) } },
                kind = ButtonKind.Secondary,
                icon = Icons.Rounded.Sync,
                enabled = !state.syncing,
            )
            GlassIconButton(Icons.Rounded.Delete, "Forget ${tv.name}", { app.phoneSync.forget(tv.serviceName) })
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
        AddField(code, { code = it.filter(Char::isDigit).take(4) }, "Pair", busy, placeholder = "4-digit code") {
            busy = true
            scope.launch {
                onMessage(runCatching { app.phoneSync.pair(pairWith, code) }.getOrElse { it.message })
                busy = false
                pairing = null
                code = ""
                found = null
            }
        }
    } else {
        found?.let { tvs ->
            if (tvs.isEmpty()) {
                Text(
                    "No TV found. Open Reel on the TV (same Wi-Fi as this phone), then search again.",
                    style = Tokens.Type.caption,
                    color = Tokens.Colors.textTertiary,
                )
            }
            tvs.forEach { tv ->
                ReelButton("Pair with ${tv.name}", { pairing = tv }, kind = ButtonKind.Secondary)
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
