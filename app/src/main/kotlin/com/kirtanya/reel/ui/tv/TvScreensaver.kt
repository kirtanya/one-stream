package com.kirtanya.reel.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.common.HeroImage
import com.kirtanya.reel.ui.common.HeroScrims
import com.kirtanya.reel.ui.common.rememberContinueWatching
import com.kirtanya.reel.ui.common.rememberWatchlist
import com.kirtanya.reel.ui.home.HomeLayout
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Tracks the remote: after [HomeLayout.screensaverMin] idle minutes on a browse screen, shows a slow
 * slideshow of My list / Continue watching backdrops with the time. Any key wakes it (and is eaten,
 * so waking never also clicks something). Off when the setting is 0 or there's nothing to show.
 */
class IdleWatch {
    var lastKeyAt by mutableLongStateOf(System.currentTimeMillis())
    var dreaming by mutableStateOf(false)
}

/** Put on the TV root: every key counts as activity; a key while dreaming only wakes. */
fun Modifier.idleWatch(w: IdleWatch): Modifier = onPreviewKeyEvent {
    w.lastKeyAt = System.currentTimeMillis()
    if (w.dreaming) { w.dreaming = false; true } else false
}

@Composable
fun TvScreensaver(w: IdleWatch, enabled: Boolean) {
    val myList = rememberWatchlist()
    val continueItems = rememberContinueWatching()
    val art = remember(myList, continueItems) {
        (continueItems.mapNotNull { it.entry.playback.backdropPath } + myList.mapNotNull { it.backdropPath }).distinct().mapNotNull { TmdbImages.backdrop(it) }.take(20)
    }
    val minutes = HomeLayout.screensaverMin
    LaunchedEffect(enabled, minutes, art.isEmpty(), w.lastKeyAt) {
        if (!enabled || minutes <= 0 || art.isEmpty()) return@LaunchedEffect
        delay(minutes * 60_000L - (System.currentTimeMillis() - w.lastKeyAt).coerceAtLeast(0))
        w.dreaming = true
    }
    AnimatedVisibility(w.dreaming, enter = fadeIn(tween(1_200)), exit = fadeOut(tween(Tokens.Motion.stateMs))) {
        Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
            HeroImage(art, Modifier.fillMaxSize())
            HeroScrims(top = false, start = false)
            var now by remember { mutableStateOf(java.time.LocalTime.now()) }
            LaunchedEffect(Unit) { while (true) { now = java.time.LocalTime.now(); delay(10_000) } }
            Column(Modifier.align(Alignment.BottomEnd).padding(horizontal = Tokens.Space.tvSafeH, vertical = Tokens.Space.tvSafeV), horizontalAlignment = Alignment.End) {
                Text(now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), style = Tokens.Type.hero, color = Tokens.Colors.text)
                Text("Press any button", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
            }
        }
    }
}
