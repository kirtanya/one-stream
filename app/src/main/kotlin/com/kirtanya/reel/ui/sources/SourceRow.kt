package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.sources.QualityBucket
import com.kirtanya.reel.core.sources.SeedHealth
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceType
import com.kirtanya.reel.core.sources.SourceView
import com.kirtanya.reel.core.sources.StartSpeed
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.phone.pressScale
import com.kirtanya.reel.ui.formatBytes
import com.kirtanya.reel.ui.theme.Tokens

/** What tapping a source does on this page. */
enum class SourceAction(val label: String, val icon: ImageVector) {
    PLAY("Play", Icons.Rounded.PlayArrow),
    DOWNLOAD("Download", Icons.Rounded.Download),
    REPLACE("Use this source", Icons.Rounded.Download),
}

/** Services open their own app, whatever the page is for. */
private fun actionFor(v: SourceView, action: SourceAction) =
    if (v.source.kind == SourceKind.EXTERNAL) "Open" to Icons.Rounded.OpenInNew else action.label to action.icon

/**
 * One source as a card that reads at a glance: quality badge and type on the left; size and seed
 * health, provider and languages, release tags, then the full release name (one line). The whole
 * card is the action (play or download); the round icon on the right says which.
 */
@Composable
fun SourceRow(
    v: SourceView,
    fits: Boolean,
    action: SourceAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press (touch) / hold OK (remote): the source's options. */
    onLongClick: (() -> Unit)? = null,
    /** Quick start's verdict: "Fast start" / "May buffer" under the facts. */
    speed: StartSpeed = StartSpeed.UNKNOWN,
) {
    val shape = RoundedCornerShape(Tokens.Radius.media)
    val (label, icon) = actionFor(v, action)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (com.kirtanya.reel.ui.common.LocalIsTv.current) Modifier else Modifier.pressScale(0.98f))
            .focusCard(shape = shape, onLongClick = onLongClick, onClick = onClick)
            .background(Tokens.Colors.surface1)
            .border(Tokens.Size.hairline, Tokens.Colors.hairline, shape)
            .padding(Tokens.Space.m),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            QualityBadge(v)
            Text(typeLabel(v.type), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, textAlign = TextAlign.Center, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            Facts(v)
            StartMark(speed)
            Text(
                listOf(v.provider).plus(v.languages).joinToString(" · "),
                style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (v.tags.isNotEmpty()) {
                Text(v.tags.joinToString(" · "), style = Tokens.Type.caption, color = Tokens.Colors.accentBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(releaseName(v.source.title), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!fits) Text("Too big to stream on this device", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        }
        // Part of the card (one focus stop per row on the TV); it only says what the card does.
        Box(Modifier.size(Tokens.Size.iconButton).clip(CircleShape).background(Tokens.Colors.tonal), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Tokens.Colors.onTonal, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The ranker's pick: glowing card with the facts that matter, its tags as pills, and one big
 * button for this page's action.
 */
@Composable
fun RecommendedCard(
    v: SourceView,
    fits: Boolean,
    action: SourceAction,
    isTv: Boolean,
    focus: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The ⋯ button next to the main one: the source's options (download, favourite, share…). */
    onMore: (() -> Unit)? = null,
    speed: StartSpeed = StartSpeed.UNKNOWN,
) {
    val shape = RoundedCornerShape(Tokens.Radius.l)
    val (label, icon) = actionFor(v, action)
    Column(
        modifier
            .fillMaxWidth()
            // No glow: a 28 dp blurred shadow re-rasterised on every scroll frame (and the TV chip can't afford it).
            // The pick stands out by its ring instead: bright on TV, a quiet hairline on the phone.
            .clip(shape)
            .background(Tokens.Colors.surface1)
            .border(if (isTv) Tokens.Size.ring else Tokens.Size.hairline, if (isTv) Tokens.Colors.accentBright else Tokens.Colors.hairlineStrong, shape)
            .padding(Tokens.Space.l),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Pill("Recommended", icon = Icons.Rounded.AutoAwesome, accent = true)
            Pill(v.bucket.label)
            Pill(typeLabel(v.type))
        }
        Facts(v, large = true)
        StartMark(speed)
        Text(
            listOf(v.provider).plus(v.languages).joinToString(" · "),
            style = Tokens.Type.label, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (v.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                v.tags.take(if (isTv) 8 else 6).forEach { Pill(it) }
            }
        }
        Text(releaseName(v.source.title), style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!fits) Text("Too big to stream on this device. Pick a smaller one below.", style = Tokens.Type.caption, color = Tokens.Colors.warn)
        // TV: keep the button a fixed width (the focus ring sits on its edge).
        Row(Modifier.padding(top = Tokens.Space.xs), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
            ReelButton(
                label, onClick,
                (if (isTv) Modifier.widthIn(min = 220.dp) else Modifier.weight(1f)).focusRequester(focus),
                kind = ButtonKind.Primary, icon = icon, large = true,
            )
            if (onMore != null) com.kirtanya.reel.ui.common.GlassIconButton(androidx.compose.material.icons.Icons.Rounded.MoreVert, "More options", onMore)
        }
    }
}

/** "9.8 GB  ▮▮▯ 412 seeds" — size, then seed health for torrents. Wraps rather than cutting words off. */
@Composable
private fun Facts(v: SourceView, large: Boolean = false) {
    val style = if (large) Tokens.Type.heading else Tokens.Type.label
    val small = if (large) Tokens.Type.label else Tokens.Type.caption
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs), itemVerticalAlignment = Alignment.CenterVertically) {
        v.source.sizeBytes?.let { Text(formatBytes(it), style = style, color = Tokens.Colors.text, maxLines = 1) }
        val health = v.health
        if (health != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                SeedBars(health)
                Text(seedText(v, large), style = small, color = Tokens.Colors.textSecondary)
            }
        } else if (v.type == SourceType.DIRECT) {
            Text(if (large) "Direct link · no peers needed" else "Direct link", style = small, color = Tokens.Colors.textSecondary)
        }
    }
}

private fun seedText(v: SourceView, large: Boolean): String = when (v.health) {
    SeedHealth.NONE -> if (large) "No one is sharing this" else "No seeds"
    SeedHealth.UNKNOWN -> "Seeds unknown"
    else -> com.kirtanya.reel.ui.plural(v.source.seeders ?: 0, "seed") + if (large) " · " + v.health!!.label else ""
}

/**
 * Three rising bars, filled by health: green when it'll start well, amber when it may crawl.
 * The colour is on the bars only: amber text misses 4.5:1 on light cards.
 */
@Composable
fun SeedBars(h: SeedHealth, modifier: Modifier = Modifier) {
    val fill = when (h) {
        SeedHealth.STRONG, SeedHealth.OK -> Tokens.Colors.good
        else -> Tokens.Colors.warn
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(6.dp, 9.dp, 12.dp).forEachIndexed { i, height ->
            Box(Modifier.width(4.dp).height(height).clip(RoundedCornerShape(1.dp)).background(if (i < h.bars) fill else Tokens.Colors.hairlineStrong))
        }
    }
}

@Composable
private fun QualityBadge(v: SourceView) {
    val label = if (v.source.kind == SourceKind.EXTERNAL) "App" else if (v.source.quality.resolution == null) "SD?" else v.bucket.label
    Pill(label, accent = v.bucket == QualityBucket.UHD || v.bucket == QualityBucket.FHD)
}

fun typeLabel(t: SourceType) = when (t) {
    SourceType.TORRENT -> "Torrent"
    SourceType.DIRECT -> "Direct"
    SourceType.SERVICE -> "Service"
}

/**
 * A release name that wraps at its dots and dashes ("Dune.Part.Two.2024.2160p…") instead of
 * mid-word; only the first line of a multi-line addon description.
 */
fun releaseName(title: String): String =
    title.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        .replace(".", ".​").replace("_", "_​").replace("-", "-​").replace("[", "​[")
