package com.kirtanya.reel.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.TitleSummary

/** Titles marked "Not interested" (synced phone → TV), as "movie:550" keys. */
@Composable
fun rememberHiddenKeys(): Set<String> {
    val keys by ReelApp.get(LocalContext.current).hidden.keys.collectAsState(initial = emptySet())
    return keys
}

/** [items] without the titles you said you're not interested in. */
fun List<TitleSummary>.withoutHidden(hidden: Set<String>): List<TitleSummary> =
    if (hidden.isEmpty()) this else filterNot { it.ref.toString() in hidden }
