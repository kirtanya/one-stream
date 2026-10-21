package com.kirtanya.reel.ui.homedisk

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.Badge
import com.kirtanya.reel.core.homedisk.BadgeTone
import com.kirtanya.reel.core.homedisk.DeleteAction
import com.kirtanya.reel.core.homedisk.DeleteChoice
import com.kirtanya.reel.core.homedisk.HomeSettings
import com.kirtanya.reel.core.homedisk.NetworkNow
import com.kirtanya.reel.core.homedisk.P2pUse
import com.kirtanya.reel.core.homedisk.P2pVerdict
import com.kirtanya.reel.core.homedisk.WaitReason
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sync.TvJob
import com.kirtanya.reel.homedisk.DiskUi
import com.kirtanya.reel.homedisk.GatePrompt
import com.kirtanya.reel.homedisk.PhoneJob
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.LocalRenderData
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens

/** Everything the home-disk screens show, from the live stores (or fixed data in screen renders). */
data class HomeView(
    val settings: HomeSettings = HomeSettings(),
    val passwordSaved: Boolean = false,
    val disk: DiskUi = DiskUi(),
    val tvPaired: Boolean = false,
    val network: NetworkNow = NetworkNow.NONE,
    val downloadVerdict: P2pVerdict = P2pVerdict.Allow,
    /** Why phone downloads wait right now (null = they run). */
    val waiting: WaitReason? = null,
    val jobs: List<PhoneJob> = emptyList(),
    /** Request ids not yet acknowledged by the TV. */
    val unsent: Set<String> = emptySet(),
    /** Downloads the network rule holds (info-hash, lowercase). */
    val held: Set<String> = emptySet(),
    /** TV: at-home downloads it keeps. */
    val tvJobs: List<TvJob> = emptyList(),
    val isTv: Boolean = false,
) {
    val setUp: Boolean get() = settings.filledIn && passwordSaved
}

@Composable
fun rememberHomeView(): HomeView {
    LocalRenderData.current?.home?.let { return it }
    val app = ReelApp.get(LocalContext.current)
    val home = app.home
    val settings by home.settings.collectAsState()
    val password by home.passwordSaved.collectAsState()
    val disk by home.disk.ui.collectAsState()
    val net by home.network.now.collectAsState()
    if (app.isTv) {
        val tv by home.agent.state.collectAsState()
        return HomeView(settings, password, disk, tvPaired = true, network = net, tvJobs = tv.jobs, isTv = true)
    }
    val phone by app.phoneSync.state.collectAsState()
    val jobs by home.client.jobs.collectAsState()
    val outbox by home.client.outbox.collectAsState()
    val held by home.gate.held.collectAsState()
    val waiting by home.gate.waiting.collectAsState()
    return HomeView(
        settings, password, disk, tvPaired = phone.tvs.any { !it.needsPairing }, network = net,
        downloadVerdict = home.gate.verdict(P2pUse.DOWNLOAD), waiting = waiting, jobs = jobs,
        unsent = outbox.mapTo(HashSet()) { it.request.id }, held = held,
    )
}

/** A Downloads badge: small, rounded, the tone tells good news from waiting. */
@Composable
fun HomeBadge(badge: Badge, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Tokens.Radius.pill)
    val (bg, fg) = when (badge.tone) {
        BadgeTone.ACCENT -> Tokens.Colors.tonal to Tokens.Colors.onTonal
        BadgeTone.WARN -> Tokens.Colors.surface2 to Tokens.Colors.warn
        BadgeTone.PLAIN -> Tokens.Colors.surface2 to Tokens.Colors.textSecondary
    }
    Row(
        modifier.clip(shape).background(bg).border(Tokens.Size.hairline, Tokens.Colors.hairline, shape).padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        Icon(if (badge.tone == BadgeTone.WARN) Icons.Rounded.Block else Icons.Rounded.Home, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(badge.label, style = Tokens.Type.caption, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Opens Settings → Home storage (from the Download-where sheet and the network prompts). */
fun openHomeStorage(context: Context) {
    context.startActivity(com.kirtanya.reel.MainActivity.openHomeStorage(context))
}

/** Sends [magnet] to the TV ("Download at home") and says what happened. */
fun downloadAtHome(context: Context, magnet: String, fileIdx: Int?, fileHint: String?, label: String, origin: DownloadOrigin?) {
    val home = ReelApp.get(context).home
    home.client.requestDownload(magnet, fileIdx, fileHint, label, origin)
    val msg = if (home.disk.ui.value.tvReachable) "Sent to your TV: “$label”" else "Your TV gets “$label” as soon as it's reachable"
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/**
 * Play from a source: torrents go through the phone's network rule first (docs/HOME_DISK.md §5.3, decisions
 * 2026-10-21: the office never, an unknown Wi-Fi asks once). Everything else, and the TV, just plays.
 */
fun gatedPlay(context: Context, source: Source?, label: String, start: () -> Unit) {
    val app = ReelApp.get(context)
    val torrent = source == null || source.kind == SourceKind.TORRENT
    if (app.isTv || !torrent) return start()
    val magnet = source?.magnetUri()
    app.home.gate.play(start, atHome = magnet?.let { m -> { downloadAtHome(context, m, source.fileIdx, source.filename, label, null) } })
}

/** The rule's questions and refusals, wherever play or download was pressed (phone only). */
@Composable
fun NetworkPromptHost() {
    val app = ReelApp.get(LocalContext.current)
    if (app.isTv) return
    val prompt by app.home.gate.prompt.collectAsState()
    prompt?.let { NetworkPrompt(it, onAnswer = { allowed -> app.home.gate.answer(allowed) }, onDismiss = app.home.gate::dismiss) }
}

@Composable
fun NetworkPrompt(p: GatePrompt, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val playing = p.use == P2pUse.STREAM
    val (title, body) = when (val v = p.verdict) {
        is P2pVerdict.Ask -> "Use this Wi-Fi for torrents?" to
            "One Stream hasn't been used on this Wi-Fi before. Allow downloads and streaming here? Never allow this on a work network."
        is P2pVerdict.Wait -> when (v.reason) {
            WaitReason.MOBILE_DATA -> "You're on mobile data" to "Downloads wait for Wi-Fi so they don't use your data plan."
            WaitReason.NO_NETWORK -> "No connection" to "The download starts by itself when you're back online."
            else -> "Not on this network" to "This Wi-Fi is marked as a work network: One Stream never uses torrents here."
        }
        is P2pVerdict.Block -> "Not on this network" to
            if (playing) "This Wi-Fi is marked as a work network: One Stream never streams torrents here." else "One Stream never uses torrents on this network."
        P2pVerdict.Allow -> return
    }
    fun then(block: () -> Unit) = { onDismiss(); block() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface1,
        title = { Text(title, style = Tokens.Type.heading, color = Tokens.Colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Text(body, style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                val v = p.verdict
                if (v is P2pVerdict.Ask) {
                    ReelButton("Allow this Wi-Fi", then { onAnswer(true); p.proceed() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Icons.Rounded.Check)
                    ReelButton("Never here (work)", then { onAnswer(false) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.Block)
                }
                if (v is P2pVerdict.Wait && v.canStartAnyway) {
                    ReelButton("Start now anyway", then(p.proceed), Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.PlayArrow)
                    p.later?.let { ReelButton("Wait for Wi-Fi", then(it), Modifier.fillMaxWidth(), kind = ButtonKind.Ghost) }
                }
                p.atHome?.let { ReelButton("Download at home instead", then(it), Modifier.fillMaxWidth(), kind = if (v is P2pVerdict.Ask || (v is P2pVerdict.Wait && v.canStartAnyway)) ButtonKind.Ghost else ButtonKind.Primary, icon = Icons.Rounded.Home) }
                if (!playing && v is P2pVerdict.Wait && !v.canStartAnyway) p.later?.let { ReelButton("Download when I'm home", then(it), Modifier.fillMaxWidth(), kind = ButtonKind.Ghost) }
            }
        },
        confirmButton = { ReelButton(if (p.verdict is P2pVerdict.Ask) "Not now" else "Close", onDismiss, kind = ButtonKind.Ghost) },
    )
}

/** Delete-where (docs/HOME_DISK.md §14): each choice as a full-width button; disk choices disabled until a disk is there. */
@Composable
fun DeleteWhereDialog(name: String, choice: DeleteChoice, onPick: (DeleteAction) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Colors.surface1,
        title = { Text("Delete “$name”?", style = Tokens.Type.heading, color = Tokens.Colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Text(choice.body, style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
                choice.warning?.let { Text(it, style = Tokens.Type.body, color = Tokens.Colors.warn) }
                choice.options.forEach { o ->
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                        ReelButton(
                            o.label, { onPick(o.action) }, Modifier.fillMaxWidth(),
                            kind = if (o.action == DeleteAction.PHONE && o.enabled) ButtonKind.Primary else ButtonKind.Secondary,
                            icon = Icons.Rounded.Delete, enabled = o.enabled,
                        )
                        o.note?.let { Text(it, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, modifier = Modifier.padding(start = Tokens.Space.l)) }
                    }
                }
            }
        },
        confirmButton = { ReelButton("Keep it", onDismiss, kind = ButtonKind.Ghost) },
    )
}

/** One at-home download on the TV's Settings page or the phone's Downloads. */
@Composable
fun AtHomeRow(label: String, badge: Badge?, action: String?, onAction: (() -> Unit)?) {
    // 4 dp above and below keeps the 40 dp Cancel button's touch area at 48 dp between rows.
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(Tokens.Radius.m)).background(Tokens.Colors.tonal),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Home, null, tint = Tokens.Colors.onTonal, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Text(label, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            badge?.let { HomeBadge(it) }
        }
        // An icon, so the title and its badge keep the width on a 360 dp phone.
        if (action != null && onAction != null) com.kirtanya.reel.ui.common.GlassIconButton(Icons.Rounded.Close, action, onAction)
    }
}
