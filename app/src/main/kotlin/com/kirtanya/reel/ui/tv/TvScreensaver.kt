package com.kirtanya.reel.ui.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.rememberContinueWatching
import com.kirtanya.reel.ui.common.rememberWatchlist
import com.kirtanya.reel.ui.home.HomeLayout
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Tracks the remote: after [HomeLayout.screensaverMin] idle minutes on a browse screen, shows a slow
 * slideshow of My list / Continue watching backdrops with the time. Any key wakes it (and is eaten,
 * so waking never also clicks something). Off when the setting is 0 or there's nothing to show.
 */
class IdleWatch {
    /**
     * When the last key came. A plain field, not State: it changes on every key press, and nothing may recompose
     * (or restart a coroutine) for that. The timer in [TvScreensaver] reads it when it wakes up.
     */
    @Volatile
    var lastKeyAt: Long = System.currentTimeMillis()
    var dreaming by mutableStateOf(false)
}

/** Put on the TV root: every key counts as activity; a key while dreaming only wakes. */
fun Modifier.idleWatch(w: IdleWatch): Modifier = onPreviewKeyEvent {
    w.lastKeyAt = System.currentTimeMillis()
    if (w.dreaming) { w.dreaming = false; true } else false
}

/**
 * One timer for the whole session: it sleeps until the idle time would be up, looks at [IdleWatch.lastKeyAt] and sleeps
 * the rest if a key came meanwhile (so a key press costs a field write, nothing else). The slideshow ([Dream], which
 * reads My list and Continue watching) is composed only while it shows.
 */
@Composable
fun TvScreensaver(w: IdleWatch, enabled: Boolean) {
    val minutes = HomeLayout.screensaverMin
    LaunchedEffect(enabled, minutes) {
        if (!enabled || minutes <= 0) return@LaunchedEffect
        while (true) {
            val left = minutes * 60_000L - (System.currentTimeMillis() - w.lastKeyAt)
            if (left > 0) { delay(left); continue }
            w.dreaming = true
            // Until woken (a key, or nothing to show): then count the idle minutes again from that moment.
            snapshotFlow { w.dreaming }.first { !it }
        }
    }
    if (w.dreaming) Dream(w)
}

@Composable
private fun Dream(w: IdleWatch) {
    val myList = rememberWatchlist()
    val continueItems = rememberContinueWatching()
    val art = remember(myList, continueItems) {
        (continueItems.mapNotNull { it.entry.playback.backdropPath } + myList.mapNotNull { it.backdropPath }).distinct().mapNotNull { TmdbImages.backdrop(it) }.take(20)
    }
    // Nothing to show (My list loads a moment after the first frame, so wait before giving up): back to counting.
    LaunchedEffect(art.isEmpty()) {
        if (!art.isEmpty()) return@LaunchedEffect
        delay(600)
        w.lastKeyAt = System.currentTimeMillis()
        w.dreaming = false
    }
    if (art.isEmpty()) return
    // Slow fade in; waking is at once (a key press is answered on the same frame).
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(1_200)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value; compositingStrategy = CompositingStrategy.ModulateAlpha }.background(Tokens.Colors.canvas)) {
        HeroImage(art, Modifier.fillMaxSize())
        HeroScrims(top = false, start = false)
        // The clock changes once a minute: sleep to the next minute, not a 10 s poll.
        var now by remember { mutableStateOf(java.time.LocalTime.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = java.time.LocalTime.now()
                delay(60_000L - now.second * 1_000L - now.nano / 1_000_000L)
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV), horizontalAlignment = Alignment.End) {
            Text(now.format(CLOCK), style = Tokens.Type.hero, color = Tokens.Colors.text)
            Text("Press any button", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
        }
    }
}

private val CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
