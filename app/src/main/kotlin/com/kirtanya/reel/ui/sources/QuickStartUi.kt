package com.kirtanya.reel.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.sources.QuickStart
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.StartProbe
import com.kirtanya.reel.core.sources.StartSpeed
import com.kirtanya.reel.data.SourceProbe
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Let the list settle this long before probing: answers keep arriving and re-rank the first few. */
private const val SETTLE_MS = 600L

/**
 * What the direct links at the top of [ranked] measured (QuickStart.probeTargets picks which). Fresh cached answers show at
 * once; the rest are probed when the list has stopped changing for a moment, and the map grows as each answer comes in.
 * No probing in screen renders ([com.kirtanya.reel.ui.common.LocalRenderData]): they pin their data and have no network.
 */
@Composable
fun rememberStartProbes(ranked: List<Source>): State<Map<String, StartProbe>> {
    val app = ReelApp.get(LocalContext.current)
    val live = com.kirtanya.reel.ui.common.LocalRenderData.current == null
    val targets = remember(ranked) { QuickStart.probeTargets(ranked).filter(SourceProbe::probeable) }
    val keys = targets.map(QuickStart::key)
    val probes = remember { mutableStateOf<Map<String, StartProbe>>(emptyMap()) }
    LaunchedEffect(keys, live) {
        if (!live || targets.isEmpty()) return@LaunchedEffect
        val known = targets.mapNotNull { s -> SourceProbe.cached(s)?.let { QuickStart.key(s) to it } }.toMap()
        if (known.isNotEmpty()) probes.value = probes.value + known
        val todo = targets.filter { QuickStart.key(it) !in known }
        if (todo.isEmpty()) return@LaunchedEffect
        delay(SETTLE_MS)
        coroutineScope {
            todo.forEach { s ->
                launch {
                    val p = SourceProbe.probe(app.http, s) ?: return@launch
                    probes.value = probes.value + (QuickStart.key(s) to p)
                }
            }
        }
    }
    return probes
}

/**
 * "Fast start" (accent, lightning) for a source that should start quickly, "May buffer" for one that probably won't;
 * nothing otherwise. Text and a small icon only: rows on the TV can't carry effects.
 */
@Composable
fun StartMark(speed: StartSpeed, modifier: Modifier = Modifier) {
    when (speed) {
        StartSpeed.FAST -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, tint = Tokens.Colors.accentBright, modifier = Modifier.size(14.dp))
            Text("Fast start", style = Tokens.Type.caption, color = Tokens.Colors.accentBright, maxLines = 1)
        }
        StartSpeed.SLOW, StartSpeed.FAILED -> Text("May buffer", modifier, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 1)
        StartSpeed.OK, StartSpeed.UNKNOWN -> Unit
    }
}
