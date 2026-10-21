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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterAltOff
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
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.store.AddonCatalog
import com.kirtanya.reel.core.store.StoreAddon
import com.kirtanya.reel.core.store.StoreChip
import com.kirtanya.reel.core.store.StoreQuery
import com.kirtanya.reel.data.SourceSetupException
import com.kirtanya.reel.ui.common.AddSourceLinkDialog
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.EmptyState
import com.kirtanya.reel.ui.common.Pill
import com.kirtanya.reel.ui.common.ReelCard
import com.kirtanya.reel.ui.phone.SearchField
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.common.RemoteImage
import com.kirtanya.reel.ui.common.RequestFocusOnce
import com.kirtanya.reel.ui.settings.AddField
import com.kirtanya.reel.ui.settings.PageList
import com.kirtanya.reel.ui.settings.PageTitle
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Addon store: search Stremio's official collection (and any catalogs you add), see what each
 * addon does, and add it in one tap (with the usual "Add this?" check). Addons that need keys or
 * choices open their setup page; on a TV, set them up on the phone and sync.
 */
@Composable
fun AddonStorePage(isTv: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val fixed = com.kirtanya.reel.ui.common.LocalRenderData.current
    val liveStore by app.addonStore.state.collectAsState()
    val store = fixed?.store ?: liveStore
    val sources = com.kirtanya.reel.ui.settings.installedSources(app)
    var query by remember { mutableStateOf(fixed?.storeQuery ?: StoreQuery()) }
    var loading by remember { mutableStateOf(fixed?.store == null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var catalogUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    if (isTv) RequestFocusOnce(searchFocus)

    LaunchedEffect(Unit) {
        if (fixed?.store != null) return@LaunchedEffect
        runCatching { app.addonStore.refresh() }
        loading = false
    }
    // Installed addons, by manifest id (configured addons have different URLs but the same id).
    val installed = remember(sources) { sources.addons.mapNotNull { runCatching { it.manifest.id }.getOrNull() }.toSet() }
    val catalogNames = remember(store.catalogs) { store.catalogs.associate { it.url to it.name } }
    val facets = remember(store.addons, installed, catalogNames) { AddonCatalog.facets(store.addons, installed) { catalogNames[it] ?: it } }
    LaunchedEffect(facets) { query = AddonCatalog.prune(query, facets) }
    val results = remember(store.addons, query, installed) { AddonCatalog.search(store.addons, query, installed) }
    val listed = remember(store.addons) { AddonCatalog.listed(store.addons).size }
    val hidden = remember(store.addons) { store.addons.count { !it.usable } }

    Box(Modifier.fillMaxSize()) {
        PageList(isTv) {
            item { PageTitle("Addon store", "Addons from Stremio's official catalog and any catalogs you add.", isTv, onBack) }
            item { SearchField(query.text, { query = query.copy(text = it) }, Modifier.widthIn(max = 860.dp).focusRequester(searchFocus), placeholder = "Search addons", voice = false) }
            item { StoreChips(facets, query) { query = it } }
            item {
                when {
                    loading && store.addons.isEmpty() -> CircularProgressIndicator(color = Tokens.Colors.accent)
                    results.isEmpty() -> EmptyState(
                        Icons.Rounded.Search,
                        if (store.addons.isEmpty()) "Couldn't load the store" else "No addons match",
                        if (store.addons.isEmpty()) "Check your connection and tap Refresh below." else "Try another word, or clear the filters.",
                        action = if (store.addons.isNotEmpty() && (query.hasChips || query.text.isNotBlank())) ({
                            ReelButton("Clear filters", { query = StoreQuery() }, kind = ButtonKind.Secondary, icon = Icons.Rounded.FilterAltOff)
                        }) else null,
                    )
                    else -> Text(
                        buildString {
                            append(if (results.size == listed) com.kirtanya.reel.ui.plural(results.size, "addon") else "${results.size} of $listed addons")
                            if (hidden > 0) append(" · $hidden catalog-only addons hidden (One Stream uses streams and subtitles)")
                            if (store.failed.isNotEmpty()) append(" · couldn't load: ${store.failed.joinToString()}")
                        },
                        style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
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
                        style = Tokens.Type.caption, color = Tokens.Colors.textSecondary,
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

@Composable
private fun StoreRow(addon: StoreAddon, installed: Boolean, isTv: Boolean, onAdd: () -> Unit, onSetUp: () -> Unit) {
    ReelCard(Modifier.fillMaxWidth().widthIn(max = 860.dp), padding = Tokens.Space.m) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m), verticalAlignment = Alignment.Top) {
        RemoteImage(addon.logo, Modifier.size(48.dp).clip(RoundedCornerShape(Tokens.Radius.s)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
            Text(addon.name, style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (addon.description.isNotBlank()) {
                Text(addon.description, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary, maxLines = if (isTv) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                if (addon.hasStreams) Pill("Streams")
                if (addon.hasSubtitles) Pill("Subtitles")
                if (addon.needsSetup) Pill("Needs setup")
                when {
                    installed -> Pill("Added", accent = true)
                    addon.needsSetup -> ReelButton(if (isTv) "Set up on phone" else "Set up", onSetUp, kind = ButtonKind.Secondary, icon = Icons.Rounded.Settings)
                    else -> ReelButton("Add", onAdd, kind = ButtonKind.Secondary, icon = Icons.Rounded.Add)
                }
            }
        }
    }
    }
}

/**
 * Filter chips from what the catalog knows: what an addon provides, whether it's added here, the
 * content it covers and which catalog lists it. Groups with nothing to choose are left out; within
 * a group several chips can be on. Two sideways rows, so the phone list stays close to the top.
 */
@Composable
private fun StoreChips(facets: com.kirtanya.reel.core.store.StoreFacets, query: StoreQuery, onChange: (StoreQuery) -> Unit) {
    @Composable
    fun <T> chip(c: StoreChip<T>, on: Boolean, toggle: () -> Unit) =
        ReelButton("${c.label} · ${c.count}", toggle, kind = if (on) ButtonKind.Primary else ButtonKind.Ghost)
    fun <T> Set<T>.flip(v: T) = if (v in this) this - v else this + v

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            items(facets.kinds, key = { "k:${it.value}" }) { c -> chip(c, c.value in query.kinds) { onChange(query.copy(kinds = query.kinds.flip(c.value))) } }
            items(facets.status, key = { "s:${it.value}" }) { c -> chip(c, c.value in query.status) { onChange(query.copy(status = query.status.flip(c.value))) } }
            if (query.hasChips) item(key = "clear") { ReelButton("Clear", { onChange(query.copy(kinds = emptySet(), status = emptySet(), types = emptySet(), catalogs = emptySet())) }, kind = ButtonKind.Ghost, icon = Icons.Rounded.FilterAltOff) }
        }
        if (facets.types.isNotEmpty() || facets.catalogs.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                items(facets.types, key = { "t:${it.value}" }) { c -> chip(c, c.value in query.types) { onChange(query.copy(types = query.types.flip(c.value))) } }
                items(facets.catalogs, key = { "c:${it.value}" }) { c -> chip(c, c.value in query.catalogs) { onChange(query.copy(catalogs = query.catalogs.flip(c.value))) } }
            }
        }
    }
}
