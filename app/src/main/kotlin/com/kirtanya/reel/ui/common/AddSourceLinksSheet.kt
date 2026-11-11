package com.kirtanya.reel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.sync.LinkKind
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.data.SourcesStore
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Several addon / repo links pasted at once: each is read first, then the user picks which to add
 * (all that can be added start ticked). Ones already installed or unreadable start unticked, so nothing
 * is added by surprise. One link keeps the single "Add this addon?" flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceLinksSheet(urls: List<String>, isTv: Boolean, onDone: () -> Unit) {
    val app = ReelApp.get(LocalContext.current)
    val fixed = LocalRenderData.current?.linkPreviews
    val installed = remember { app.sources.state.value.let { s -> s.addons.map { it.url }.toSet() + s.repos.map { it.url } } }
    val previews = remember(urls) { mutableStateListOf<Load<SourcesStore.LinkPreview>>().apply { addAll(fixed ?: urls.map { Load.Loading }) } }
    val picked = remember(urls) { mutableStateMapOf<Int, Boolean>() }
    // Per row after Add: null = not tried, "" = added, else the reason it failed.
    val results = remember(urls) { mutableStateMapOf<Int, String>() }
    var adding by remember { mutableStateOf(false) }
    val done = results.isNotEmpty() && !adding
    val buttonFocus = remember { FocusRequester() }
    if (isTv) RequestFocusOnce(buttonFocus, key = done)

    if (fixed == null) LaunchedEffect(urls) {
        coroutineScope {
            urls.mapIndexed { i, url ->
                async {
                    previews[i] = try {
                        Load.Ready(app.sources.preview(url))
                    } catch (e: SourceSetupException) {
                        Load.Failed(e.message ?: "Couldn't read it")
                    } catch (e: Exception) {
                        Load.Failed("Couldn't read it: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    fun isPicked(i: Int): Boolean {
        val p = previews[i] as? Load.Ready ?: return false
        return picked[i] ?: (p.value.url !in installed)
    }
    val chosen = previews.indices.filter(::isPicked)
    val reading = previews.any { it is Load.Loading }
    val allAddons = previews.all { (it as? Load.Ready)?.value?.kind != LinkKind.PLUGIN_REPO }
    val noun = if (allAddons) "addons" else "sources"

    ModalBottomSheet(
        onDismissRequest = { if (!adding) onDone() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !adding }),
        containerColor = Tokens.Colors.surface1,
        shape = RoundedCornerShape(topStart = Tokens.Radius.xl, topEnd = Tokens.Radius.xl),
        dragHandle = { Box(Modifier.padding(top = Tokens.Space.m).size(width = 40.dp, height = 4.dp)) { GradientProgress(1f, height = 4.dp) } },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tokens.Space.phoneGutter, vertical = Tokens.Space.l),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                val added = results.values.count { it.isEmpty() }
                Text(
                    if (done) "Added $added of ${results.size}" else "Add ${urls.size} $noun?",
                    style = Tokens.Type.title,
                    color = Tokens.Colors.text,
                )
                Text(
                    when {
                        done -> "Anything that failed is listed with the reason."
                        reading -> "Reading the links…"
                        else -> "Untick any you don't want. Streaming or downloading content you don't have rights to is illegal in most countries."
                    },
                    style = Tokens.Type.body,
                    color = Tokens.Colors.textSecondary,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                itemsIndexed(urls) { i, url ->
                    LinkRow(url, previews[i], isPicked(i), url in installed || (previews[i] as? Load.Ready)?.value?.url in installed, results[i], enabled = !adding && !done) {
                        picked[i] = !isPicked(i)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
                val focus = Modifier.weight(1f).focusRequester(buttonFocus)
                if (done) {
                    ReelButton("Done", onDone, focus, kind = ButtonKind.Primary, large = true)
                } else {
                    ReelButton("Cancel", onDone, Modifier.weight(1f), kind = ButtonKind.Ghost, enabled = !adding, large = true)
                    ReelButton(
                        when {
                            adding -> "Adding…"
                            chosen.isEmpty() -> "Add"
                            else -> "Add ${chosen.size}"
                        },
                        {
                            if (adding || chosen.isEmpty()) return@ReelButton
                            adding = true
                            // The app's scope: adding several takes a while and must finish even if the sheet goes.
                            app.appScope.launch {
                                // One at a time: each add rewrites the saved sources list.
                                for (i in chosen) {
                                    val p = (previews[i] as Load.Ready).value
                                    results[i] = try {
                                        app.sources.addPreviewed(p); ""
                                    } catch (e: SourceSetupException) {
                                        e.message ?: "Couldn't add it"
                                    } catch (e: Exception) {
                                        "Couldn't add it: ${e.message}"
                                    }
                                }
                                adding = false
                            }
                        },
                        focus,
                        kind = ButtonKind.Primary,
                        icon = Icons.Rounded.Add,
                        enabled = !adding && !reading && chosen.isNotEmpty(),
                        large = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkRow(url: String, preview: Load<SourcesStore.LinkPreview>, checked: Boolean, already: Boolean, result: String?, enabled: Boolean, onToggle: () -> Unit) {
    val ready = preview as? Load.Ready
    Row(
        Modifier.fillMaxWidth()
            .then(if (ready != null && enabled) Modifier.focusCard(shape = RoundedCornerShape(Tokens.Radius.m), onClick = onToggle) else Modifier)
            .padding(vertical = Tokens.Space.s, horizontal = Tokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            when {
                result == "" -> Icon(Icons.Rounded.CheckCircle, "Added", tint = Tokens.Colors.accent)
                result != null || preview is Load.Failed -> Icon(Icons.Rounded.ErrorOutline, "Failed", tint = Tokens.Colors.warn)
                preview is Load.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = Tokens.Colors.accent, strokeWidth = 2.dp)
                else -> Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Tokens.Colors.accent,
                        checkmarkColor = Tokens.Colors.onAccent,
                        uncheckedColor = Tokens.Colors.textSecondary,
                    ),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            val host = android.net.Uri.parse(url).host ?: url
            Text(ready?.value?.name ?: host, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = when {
                result != null && result.isNotEmpty() -> result
                result == "" -> "Added"
                preview is Load.Failed -> preview.message
                preview is Load.Loading -> "Reading…"
                already -> "Already added"
                else -> ready?.value?.detail?.substringBefore('\n').orEmpty()
            }
            val warn = (result != null && result.isNotEmpty()) || preview is Load.Failed
            if (line.isNotBlank()) Text(line, style = Tokens.Type.caption, color = if (warn) Tokens.Colors.warn else Tokens.Colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(url, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
