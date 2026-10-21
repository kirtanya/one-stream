package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.home.Density
import com.kirtanya.reel.ui.home.HeroSource
import com.kirtanya.reel.ui.home.HomeLayout
import com.kirtanya.reel.ui.home.PosterStyle
import com.kirtanya.reel.ui.home.RowIds
import com.kirtanya.reel.ui.home.TvStart
import com.kirtanya.reel.ui.theme.Tokens

/** Settings → Home screen: the hero, poster look, and which rows show in what order. */
internal fun homeLayoutSummary(isTv: Boolean): String = listOfNotNull(
    if (isTv) null else "Hero: ${HomeLayout.hero.label}",
    HomeLayout.posterStyle.label.lowercase() + " rows",
    HomeLayout.hidden.size.takeIf { it > 0 }?.let { "$it hidden" },
    if (isTv && HomeLayout.tvStart != TvStart.Home) "opens on ${HomeLayout.tvStart.label}" else null,
).joinToString(" · ")

internal fun LazyListScope.homeLayoutItems(isTv: Boolean) {
    // The TV has no hero carousel (its backdrop follows focus).
    if (!isTv) item(key = "hero") {
        Section("Hero", note = "The big picture at the top of Home.") {
            Choices(HeroSource.entries, HomeLayout.hero, { it.label }, HomeLayout::chooseHero)
            ToggleRow("Moves on by itself", "Shows the next title every few seconds.", HomeLayout.heroAutoAdvance) {
                HomeLayout.chooseHeroAutoAdvance(!HomeLayout.heroAutoAdvance)
            }
        }
    }
    item(key = "look") {
        Section("Rows") {
            Text("Look", style = Tokens.Type.label, color = Tokens.Colors.text)
            Choices(PosterStyle.entries, HomeLayout.posterStyle, { it.label }, HomeLayout::choosePosterStyle)
            if (!isTv) {
                Text("Size", style = Tokens.Type.label, color = Tokens.Colors.text)
                Choices(Density.entries, HomeLayout.density, { "${it.label} · ${it.across} across" }, HomeLayout::chooseDensity)
            }
        }
    }
    if (isTv) {
        item(key = "tv") {
            Section("TV", note = "The screensaver shows backdrops from My list after the remote has been idle.") {
                Text("Open on", style = Tokens.Type.label, color = Tokens.Colors.text)
                Choices(TvStart.entries, HomeLayout.tvStart, { it.label }, HomeLayout::chooseTvStart)
                Text("Screensaver", style = Tokens.Type.label, color = Tokens.Colors.text)
                Choices(listOf(0, 2, 5, 10), HomeLayout.screensaverMin, { if (it == 0) "Off" else "$it min" }, HomeLayout::chooseScreensaver)
            }
        }
    }
    item(key = "order") {
        Section("Order", note = "Hidden rows stay hidden until you show them again.") {
            val rows = HomeLayout.all()
            rows.forEachIndexed { i, id -> RowLine(id, i, rows.size) }
        }
    }
    item(key = "reset") { ReelButton("Reset Home", HomeLayout::reset, kind = ButtonKind.Ghost, icon = Icons.Rounded.Refresh) }
}

@Composable
private fun <T> Choices(options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    // Chips wrap rather than squeeze (font 1.3 on a 360 dp phone).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        options.forEach { o -> ReelButton(label(o), { onChoose(o) }, kind = if (o == chosen) ButtonKind.Primary else ButtonKind.Ghost) }
    }
}

/** One row of Home: its name, up / down, and show / hide. */
@Composable
private fun RowLine(id: String, index: Int, count: Int) {
    val shown = id !in HomeLayout.hidden
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Column(Modifier.weight(1f)) {
            Text(RowIds.label(id), style = Tokens.Type.label, color = if (shown) Tokens.Colors.text else Tokens.Colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!shown) Text("Hidden", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
        }
        if (index > 0) GlassIconButton(Icons.Rounded.KeyboardArrowUp, "Move ${RowIds.label(id)} up", { HomeLayout.move(id, -1) })
        if (index < count - 1) GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Move ${RowIds.label(id)} down", { HomeLayout.move(id, 1) })
        GlassIconButton(
            if (shown) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            if (shown) "Hide ${RowIds.label(id)}" else "Show ${RowIds.label(id)}",
            { HomeLayout.setShown(id, !shown) },
        )
    }
}
