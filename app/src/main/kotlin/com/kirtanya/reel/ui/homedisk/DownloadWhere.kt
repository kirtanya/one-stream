package com.kirtanya.reel.ui.homedisk

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.DownloadWhere
import com.kirtanya.reel.core.homedisk.DownloadWhereSheet
import com.kirtanya.reel.core.homedisk.WhereRow
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GradientProgress
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.settings.ToggleRow
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Download → where? on the phone's Sources page (docs/HOME_DISK.md §17.3): asks At home / On this phone
 * (remembered by default), applies the network rule to "On this phone". The TV downloads as before.
 */
class DownloadFlow internal constructor(private val context: Context, private val label: String, private val origin: DownloadOrigin, private val isTv: Boolean) {
    internal var asking by mutableStateOf<Source?>(null)
    internal var changing by mutableStateOf(false)

    /** The Download button on a source. */
    fun download(s: Source) {
        if (isTv || s.kind != SourceKind.TORRENT) return com.kirtanya.reel.ui.sources.download(context, s, label, origin)
        val home = ReelApp.get(context).home
        val where = home.settings.value.downloadWhere
        val atHomeOk = home.setUp && home.client.pairedTv() != null
        when {
            where == DownloadWhere.AT_HOME && atHomeOk -> atHome(s)
            where == DownloadWhere.ON_PHONE -> onPhone(s)
            else -> asking = s
        }
    }

    internal fun atHome(s: Source) {
        val magnet = s.magnetUri() ?: return
        downloadAtHome(context, magnet, s.fileIdx, s.filename, label, origin)
    }

    internal fun onPhone(s: Source) {
        val home = ReelApp.get(context).home
        val hash = s.magnetUri()?.let { Magnet.parse(it)?.infoHash }
        home.gate.download(
            hash,
            add = { com.kirtanya.reel.ui.sources.download(context, s, label, origin) },
            atHome = if (home.setUp && home.client.pairedTv() != null) ({ atHome(s) }) else null,
        )
    }

    /** "At home · change" under Download: the remembered choice, or null when it asks each time. */
    fun rememberedLine(): String? =
        if (isTv) null else DownloadWhereSheet.rememberedLine(ReelApp.get(context).home.settings.value.downloadWhere)

    fun change() {
        changing = true
    }
}

@Composable
fun rememberDownloadFlow(label: String, origin: DownloadOrigin, isTv: Boolean): DownloadFlow {
    val context = LocalContext.current
    val flow = remember(label, origin, isTv) { DownloadFlow(context, label, origin, isTv) }
    val s = flow.asking
    if (s != null || flow.changing) {
        DownloadWhereSheetView(
            pickOnly = s == null,
            onPick = { where, remember ->
                val home = ReelApp.get(context).home
                if (remember || s == null) home.update { it.copy(downloadWhere = where) }
                flow.asking = null
                flow.changing = false
                if (s != null) if (where == DownloadWhere.AT_HOME) flow.atHome(s) else flow.onPhone(s)
            },
            onDismiss = { flow.asking = null; flow.changing = false },
        )
    }
    return flow
}

/** The sheet itself: two large rows and "Remember my choice" (on by default). [pickOnly]: changing the remembered choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadWhereSheetView(pickOnly: Boolean, onPick: (DownloadWhere, Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = rememberHomeView()
    val rows = DownloadWhereSheet.rows(view.setUp, view.tvPaired, view.disk.tvReachable, view.settings.copyPhoneDownloads, view.downloadVerdict)
    var remember by remember { mutableStateOf(true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Colors.surface1,
        shape = RoundedCornerShape(topStart = Tokens.Radius.xl, topEnd = Tokens.Radius.xl),
        dragHandle = { Box(Modifier.padding(top = Tokens.Space.m).size(width = 40.dp, height = 4.dp)) { GradientProgress(1f, height = 4.dp) } },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        ) {
            Text(if (pickOnly) "When I tap Download" else "Download where?", style = Tokens.Type.title, color = Tokens.Colors.text)
            rows.forEach { row -> WhereRowView(row, onClick = { onPick(row.where, remember) }) }
            if (!rows[0].enabled && !view.setUp) {
                ReelButton("Set up home storage", { onDismiss(); openHomeStorage(context) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Settings)
            }
            if (pickOnly) {
                ReelButton("Ask me each time", { onPick(DownloadWhere.ASK, true) }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            } else {
                ToggleRow("Remember my choice", "Change it later under Download or in Settings → Home storage.", remember) { remember = !remember }
            }
        }
    }
}

@Composable
private fun WhereRowView(row: WhereRow, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    Row(
        Modifier.fillMaxWidth()
            // A row that can't be picked keeps readable text; its status line says why (and the icon goes grey).
            .then(if (row.enabled) Modifier.focusCard(shape = shape, onClick = onClick) else Modifier)
            .clip(shape)
            .background(Tokens.Colors.surface2)
            .padding(Tokens.Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (row.enabled) Tokens.Colors.tonal else Tokens.Colors.surface3), contentAlignment = Alignment.Center) {
            Icon(if (row.where == DownloadWhere.AT_HOME) Icons.Rounded.Home else Icons.Rounded.PhoneAndroid, null, tint = if (row.enabled) Tokens.Colors.onTonal else Tokens.Colors.textSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text(row.title, style = Tokens.Type.heading, color = Tokens.Colors.text)
            Text(row.body, style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
            row.status?.let { Text(it, style = Tokens.Type.caption, color = if (row.warn) Tokens.Colors.warn else Tokens.Colors.accentBright) }
        }
    }
}
