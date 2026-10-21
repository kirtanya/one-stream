package com.kirtanya.reel.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.data.SourcesState
import com.kirtanya.reel.data.SourcesStore
import com.kirtanya.reel.data.StoreState
import com.kirtanya.reel.engine.TorrentSnapshot

/**
 * Screen renders (tests) only: fixed data in place of the live stores, so every state can be drawn
 * on the Mac (see ScreenRenders). Null in the app. TMDB data goes through [LocalCatalog] instead.
 */
class RenderData(
    val torrents: List<TorrentSnapshot>? = null,
    /** Stream cache and free space, in bytes. */
    val storage: Pair<Long, Long>? = null,
    /** The Sources screen's search, whatever the title (null inside = still getting ready). */
    val sourceSearch: (() -> SourceSearch?)? = null,
    val installed: SourcesState? = null,
    val store: StoreState? = null,
    val linkPreview: Load<SourcesStore.LinkPreview>? = null,
    /** Which title / episode each download is for (info-hash → origin). */
    val origins: Map<String, com.kirtanya.reel.core.library.DownloadOrigin>? = null,
    /** Sources page: chips already picked; [confirmReplace] opens the "Replace this download?" dialog for the pick. */
    val sourceFilters: com.kirtanya.reel.core.sources.SourceFilters? = null,
    val confirmReplace: Boolean = false,
    /** Addon store: search text and chips already picked. */
    val storeQuery: com.kirtanya.reel.core.store.StoreQuery? = null,
)

val LocalRenderData = staticCompositionLocalOf<RenderData?> { null }
