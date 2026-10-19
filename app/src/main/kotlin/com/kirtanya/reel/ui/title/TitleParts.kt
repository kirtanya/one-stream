package com.kirtanya.reel.ui.title

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.Provider
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.Retry
import com.kirtanya.reel.ui.common.focusCard
import com.kirtanya.reel.ui.common.rememberLoad
import com.kirtanya.reel.ui.theme.Tokens

@Composable
fun rememberTitle(ref: TitleRef): Pair<Load<TitleDetails>, Retry> {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    return rememberLoad(ref) { tmdb.details(ref) }
}

@Composable
fun rememberSeason(tvId: Int, season: Int?): Pair<Load<List<Episode>>, Retry> {
    val tmdb = ReelApp.get(LocalContext.current).tmdb
    return rememberLoad(tvId to season) { if (season == null) emptyList() else tmdb.season(tvId, season) }
}

/** "2019 · 2h 19m · A · ★ 8.4" */
fun detailsMeta(d: TitleDetails): String = listOfNotNull(
    d.summary.year?.toString(),
    d.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
    if (d.seasons.isNotEmpty()) d.seasons.count { it.number > 0 }.let { if (it == 1) "1 season" else "$it seasons" } else null,
    d.certification,
    d.summary.rating?.let { "★ %.1f".format(it) },
).joinToString(" · ")

/** Opens the provider's app (or the JustWatch page) and tells the user if neither works. */
@Composable
fun rememberProviderOpener(fallbackLink: String?): (Provider) -> Unit {
    val context = LocalContext.current
    val apps = ReelApp.get(context).providerApps
    return { provider ->
        if (!apps.open(provider, fallbackLink)) {
            Toast.makeText(context, "${provider.name} isn't installed on this device", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Logo + name. Focusable, so it works as a TV button too. */
@Composable
fun ProviderChip(provider: Provider, label: String = provider.name, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .focusCard(shape = RoundedCornerShape(Tokens.Radius.pill), onClick = onClick)
            .background(Tokens.Colors.surfaceHigh)
            .heightIn(min = Tokens.Size.touchTarget)
            .padding(start = Tokens.Space.xs, end = Tokens.Space.l, top = Tokens.Space.xs, bottom = Tokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        RemoteImage(TmdbImages.logo(provider.logoPath), Modifier.size(36.dp).clip(RoundedCornerShape(Tokens.Radius.s)))
        Text(label, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1)
    }
}

/** Episode as a 16:9 still with "3. Title" and runtime underneath. */
@Composable
fun EpisodeCard(episode: Episode, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null) {
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        RemoteImage(
            TmdbImages.still(episode.stillPath),
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusCard(onFocus = onFocus, onClick = onClick),
        )
        Text("${episode.number}. ${episode.name}", style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        episode.runtimeMin?.let { Text("${it}m", style = Tokens.Type.caption, color = Tokens.Colors.textMuted) }
    }
}

const val SOURCES_SOON = "Play from sources arrives in the next update."
