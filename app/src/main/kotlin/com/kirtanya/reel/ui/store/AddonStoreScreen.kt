package com.kirtanya.reel.ui.store

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.store.AddonCatalog
import com.kirtanya.reel.core.store.StoreAddon
import com.kirtanya.reel.core.store.StoreFilter
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.ui.common.AddSourceLinkDialog
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.settings.AddField
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Addon store: search Stremio's official collection (and any catalogs you add), see what each
 * addon does, and add it in one tap (with the usual "Add this?" check). Addons that need keys or
 * choices open their setup page; on a TV, set them up on the phone and sync.
 */
@Composable
fun AddonStoreScreen(isTv: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val store by app.addonStore.state.collectAsState()
    val sources by app.sources.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(StoreFilter.ALL) }
    var loading by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf<String?>(null) }
    var catalogUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    if (isTv) RequestFocusOnce(searchFocus)

    LaunchedEffect(Unit) {
        runCatching { app.addonStore.refresh() }
        loading = false
    }
    // Installed addons, by manifest id (configured addons have different URLs but the same id).
    val installed = remember(sources) { sources.addons.mapNotNull { runCatching { it.manifest.id }.getOrNull() }.toSet() }
    val results = remember(store.addons, query, filter) { AddonCatalog.search(store.addons, query, filter) }
    val hidden = remember(store.addons) { store.addons.count { !it.usable } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val side = if (isTv) Tokens.Space.tvSafeH else Tokens.Space.phoneGutter
        Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side, end = side, top = if (isTv) Tokens.Space.tvSafeV else Tokens.Space.l, bottom = Tokens.Space.xxl),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            ) {
                item {
                    Row(Modifier.then(if (isTv) Modifier else Modifier.statusBarsPadding()), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Addon store", style = Tokens.Type.display, color = Tokens.Colors.text)
                            Text("Addons from Stremio's official catalog and any catalogs you add.", style = Tokens.Type.caption, color = Tokens.Colors.textTertiary)
                        }
                        GlassIconButton(Icons.Rounded.Close, "Close", onClose)
                    }
                }
                item {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth().widthIn(max = 860.dp).focusRequester(searchFocus), singleLine = true,
                        shape = RoundedCornerShape(Tokens.Radius.pill),
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Tokens.Colors.textSecondary) },
                        placeholder = { Text("Search addons") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Tokens.Colors.surface2, unfocusedContainerColor = Tokens.Colors.surface2,
                            focusedBorderColor = Tokens.Colors.accentBright, unfocusedBorderColor = Tokens.Colors.hairlineStrong,
                        ),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                        StoreFilter.entries.forEach { f ->
                            ReelButton(f.label, { filter = f }, kind = if (filter == f) ButtonKind.Primary else ButtonKind.Ghost)
                        }
                    }
                }
                item {
                    when {
                        loading && store.addons.isEmpty() -> CircularProgressIndicator(color = Tokens.Colors.accent)
                        else -> Text(
                            buildString {
                                append("${results.size} addons")
                                if (hidden > 0) append(" · $hidden catalog-only addons hidden (Reel uses streams and subtitles)")
                                if (store.failed.isNotEmpty()) append(" · couldn't load: ${store.failed.joinToString()}")
                            },
                            style = Tokens.Type.caption, color = Tokens.Colors.textTertiary,
                        )
                    }
                }
                items(results, key = { it.manifestUrl }) { addon ->
                    StoreRow(
                        addon, installed = addon.id in installed, isTv = isTv,
                        onAdd = { adding = addon.manifestUrl },
                        onSetUp = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(addon.configureUrl)))
                            } catch (e: ActivityNotFoundException) {
                                note = "No browser here. Set ${addon.name} up on your phone; it will sync to this TV."
                            }
                        },
                    )
                }
                item {
                    Column(Modifier.widthIn(max = 860.dp).padding(top = Tokens.Space.xl), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        Text("Catalogs", style = Tokens.Type.heading, color = Tokens.Colors.text)
                        Text(
                            "Add another addon catalog by its link. You choose what to add — streaming content you don't have rights to is illegal in most countries.",
                            style = Tokens.Type.caption, color = Tokens.Colors.textTertiary,
                        )
                        store.catalogs.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                                Text(c.name, style = Tokens.Type.label, color = Tokens.Colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.builtIn) Pill("Built in") else GlassIconButton(Icons.Rounded.Delete, "Remove ${c.name}", { app.addonStore.removeCatalog(c.url) })
                            }
                        }
                        AddField(catalogUrl, { catalogUrl = it }, "Add catalog", busy, placeholder = "https://…") {
                            busy = true
                            app.appScope.launch {
                                note = try {
                                    app.addonStore.addCatalog(catalogUrl).also { catalogUrl = "" }
                                } catch (e: SourceSetupException) {
                                    e.message
                                }
                                busy = false
                            }
                        }
                        ReelButton("Refresh", { app.appScope.launch { app.addonStore.refresh(force = true) } }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
                        note?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
                    }
                }
            }
            // Same "Add this?" check as Install links: shows what it is, installs exactly that.
            adding?.let { link -> key(link) { AddSourceLinkDialog(link, isTv = isTv, onDone = { adding = null }) } }
        }
    }
}

@Composable
private fun StoreRow(addon: StoreAddon, installed: Boolean, isTv: Boolean, onAdd: () -> Unit, onSetUp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 860.dp).clip(RoundedCornerShape(Tokens.Radius.m)).background(Tokens.Colors.surface1).padding(Tokens.Space.m),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
        verticalAlignment = Alignment.Top,
    ) {
        RemoteImage(addon.logo, Modifier.size(48.dp).clip(RoundedCornerShape(Tokens.Radius.s)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Text(addon.name, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (addon.description.isNotBlank()) {
                Text(addon.description, style = Tokens.Type.caption, color = Tokens.Colors.textTertiary, maxLines = if (isTv) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                if (addon.hasStreams) Pill("Streams")
                if (addon.hasSubtitles) Pill("Subtitles")
                if (addon.needsSetup) Pill("Needs setup")
                when {
                    installed -> Pill("Added", accent = true)
                    addon.needsSetup -> ReelButton(if (isTv) "Set up on phone" else "Set up", onSetUp, kind = ButtonKind.Secondary, icon = Icons.Rounded.Settings)
                    else -> ReelButton("Add", onAdd, kind = ButtonKind.Primary, icon = Icons.Rounded.Add)
                }
            }
        }
    }
}
