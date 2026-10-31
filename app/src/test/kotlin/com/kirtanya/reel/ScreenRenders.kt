package com.kirtanya.reel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kirtanya.reel.core.tmdb.Provider
import com.kirtanya.reel.data.SourcesStore
import com.kirtanya.reel.core.sync.LinkKind
import com.kirtanya.reel.ui.DownloadsScreen
import com.kirtanya.reel.ui.common.AddLinkSheet
import com.kirtanya.reel.ui.common.AddSourceLinkDialog
import com.kirtanya.reel.ui.common.Catalog
import com.kirtanya.reel.ui.common.LocalCatalog
import com.kirtanya.reel.ui.common.LocalRenderData
import com.kirtanya.reel.ui.common.Load
import com.kirtanya.reel.ui.common.RenderData
import com.kirtanya.reel.ui.common.TopFade
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.nav.Navigator
import com.kirtanya.reel.ui.nav.Tab
import com.kirtanya.reel.ui.phone.BottomBar
import com.kirtanya.reel.ui.phone.PhoneApp
import com.kirtanya.reel.ui.phone.PhoneSearch
import com.kirtanya.reel.ui.phone.RecentSearches
import com.kirtanya.reel.ui.settings.SettingsPage
import com.kirtanya.reel.ui.settings.SettingsScreen
import com.kirtanya.reel.ui.theme.AccentState
import com.kirtanya.reel.ui.theme.ReelTheme
import com.kirtanya.reel.ui.theme.ThemeMode
import com.kirtanya.reel.ui.theme.Tokens
import com.kirtanya.reel.ui.tv.TvApp
import com.kirtanya.reel.ui.tv.TvSearch
import com.kirtanya.reel.ui.tv.TvTopBar
import com.kirtanya.reel.ui.tv.TvTopBarHeight
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction

/**
 * Renders every screen × state on the Mac with fixed data (no network), to check layouts without a device:
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.ScreenRenders'
 * PNGs land in app/build/screens: phone/<state>-<dark|light>-<font %>.png (360 × 800 dp, font scale 1.0 and 1.3)
 * and tv/<state>.png (960 × 540 dp). Artwork is painted by [FakeArt]; TMDB answers come from [FakeCatalog].
 * The design rules (design/DesignRules.kt) reuse every screen here: they override [onScreenReady].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenRenders.PHONE)
open class ScreenRenders(protected val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    /**
     * Screens that read the clock (the phone home greeting) must render the same at any hour, or the
     * design-rule baselines flip ("Good evening" vs "Late-night show?"). Shift the default zone so it is
     * always 19:xx locally while the test runs.
     */
    @org.junit.Before fun pinEvening() {
        val utcHour = java.time.LocalTime.now(java.time.ZoneOffset.UTC).hour
        val offset = ((19 - utcHour + 36) % 24) - 12
        savedZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(java.time.ZoneOffset.ofHours(offset)))
    }

    @org.junit.After fun restoreZone() {
        savedZone?.let { java.util.TimeZone.setDefault(it) }
    }

    private var savedZone: java.util.TimeZone? = null

    data class Variant(val dark: Boolean, val fontScale: Float) {
        override fun toString() = (if (dark) "dark" else "light") + "-" + (fontScale * 100).toInt()
    }

    private val app get() = RuntimeEnvironment.getApplication() as ReelApp

    /** The screen's focus manager (the design rules move TV focus around with it). */
    protected var focusManager: androidx.compose.ui.focus.FocusManager? = null

    /** False in subclasses that check screens instead of saving PNGs (the brand art isn't a screen). */
    protected open val rendersBrand get() = true

    @OptIn(ExperimentalRoborazziApi::class)
    private fun render(
        name: String,
        tv: Boolean = false,
        catalog: Catalog = FakeCatalog(),
        data: RenderData? = null,
        history: Boolean = false,
        /** The seeded raw file (Tears of Steel) matched on TMDB first (ArtMatcher): artwork instead of its letter tile. */
        artMatched: Boolean = false,
        /** Dialogs and sheets are windows of their own: capture the whole screen. */
        windows: Boolean = false,
        waitMs: Long = 1_800,
        /** Scroll the page's list to its end first (the last row must clear the floating tab bar). */
        scrollToEnd: Boolean = false,
        /** Scroll the page's list down by this many dp first (sections further down). */
        scrollDp: Int = 0,
        /** TV: press ↓ this many times first (focus moves down the page). */
        focusDown: Int = 0,
        content: @Composable () -> Unit,
    ) {
        if (tv) assumeTrue("TV renders run once", variant == DEFAULT)
        AccentState.chooseTheme(if (tv || variant.dark) ThemeMode.Dark else ThemeMode.Light)
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).components { add(FakeArt) }.build() }
        Fake.clearHistory(app)
        if (history) Fake.seedHistory(app)
        // Raw-file artwork: fake TMDB only (no render touches the network); matched up front when asked.
        app.artMatcher.lookup = if (artMatched) ArtFakes.tearsOfSteel else ArtFakes.nothing
        if (artMatched) kotlinx.coroutines.runBlocking { app.artMatcher.enrichHistory("render", force = true) }
        // Home's hero pages by itself: drive the clock by hand so the test never waits on it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // The TV has no touch screen: keyboard (remote) input mode, so focus shows as it does there.
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            if (tv) androidx.compose.runtime.LaunchedEffect(Unit) {
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            }
            val density = LocalDensity.current
            focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (tv) 1f else variant.fontScale),
                LocalCatalog provides catalog,
                LocalRenderData provides data,
            ) {
                ReelTheme { Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) { content() } }
            }
        }
        settle(waitMs)
        if (scrollToEnd) {
            // The page's own vertical list (the first one; rows inside it scroll sideways).
            val list = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)[0]
            // Rows load and animate in: scroll, let them settle, scroll again, until the end really is the end.
            repeat(3) { list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }; settle(900) }
            settle(1_200)
        }
        if (scrollDp > 0) {
            // ScrollBy, not performScrollToNode: the hero slideshow never idles, so anything that waits for idle hangs.
            val list = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)[0]
            val px = scrollDp * app.resources.displayMetrics.density
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
            settle(1_200)
        }
        repeat(focusDown) {
            focusManager?.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            settle(500)
        }
        onScreenReady(name, tv, windows)
    }

    /** The screen has settled: save it as a PNG. */
    @OptIn(ExperimentalRoborazziApi::class)
    protected open fun onScreenReady(name: String, tv: Boolean, windows: Boolean) {
        val path = if (tv) "build/screens/tv/$name.png" else "build/screens/phone/$name-$variant.png"
        val options = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = if (tv) 0.75 else 0.5))
        if (windows) captureScreenRoboImage(path, options) else compose.onRoot().captureRoboImage(path, options)
    }

    /** Lets loads finish while advancing Compose's clock (animations, the colour cross-fade). */
    protected fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(80)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun assumeDark() = assumeTrue("dark only", variant.dark)

    private fun phone(nav: Navigator.() -> Unit = {}): @Composable () -> Unit = {
        PhoneApp(incomingMagnet = null, onIncomingHandled = {}, nav = Navigator().apply(nav))
    }

    private fun tvApp(nav: Navigator.() -> Unit = {}): @Composable () -> Unit = {
        TvApp(incomingMagnet = null, onIncomingHandled = {}, nav = Navigator().apply(nav))
    }

    /** A tab screen with the phone's bars, for screens opened in a particular state. */
    @Composable
    private fun PhoneTab(tab: Tab, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            content()
            TopFade(extra = 8.dp)
            Box(Modifier.align(Alignment.BottomCenter)) { BottomBar(tab) {} }
        }
    }

    /** A TV tab screen under the tab bar (remote only: no touch padding). */
    @Composable
    private fun TvTab(tab: Tab, padTop: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Box(Modifier.fillMaxSize()) {
                Box(if (padTop) Modifier.padding(top = TvTopBarHeight) else Modifier) { content() }
                TvTopBar(Navigator().apply { select(tab) })
            }
        }
    }

    // ─── Phone ───────────────────────────────────────────────────────────────────────────────

    @Test fun home() = render("home", history = true, content = phone())
    // Home and Search end renders are dark only: in light, the poster meta line (textTertiary, 4.27:1) is a known
    // contrast issue, baselined on the collection grids and waiting on the user's call (DECISIONS 2026-10-21).
    // A Continue watching card for a raw file (Tears.of.Steel.2012.1080p…) once its name matched on TMDB;
    // "home" shows the same card before, on its letter tile.
    @Test fun homeArtMatched() = render("home-art-matched", history = true, artMatched = true, content = phone())
    @Test fun homeEnd() = assumeDark().let { render("home-end", history = true, scrollToEnd = true, content = phone()) }
    @Test fun homeFirstRun() = render("home-first-run", content = phone())
    @Test fun homeLoading() = render("home-loading", catalog = FakeCatalog(Answer.Loading), content = phone())
    @Test fun homeError() = render("home-error", catalog = FakeCatalog(Answer.Fail), content = phone())
    @Test fun homeEmpty() = render("home-empty", catalog = FakeCatalog(Answer.Empty), content = phone())

    @Test fun search() {
        RecentSearches.clear(app)
        listOf("interstellar", "the night manager season 2", "a very long search that someone typed on the bus", "ran").forEach { RecentSearches.add(app, it) }
        render("search", content = phone { select(Tab.Search) })
    }
    @Test fun searchResults() = render("search-results") { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "dune") } }
    @Test fun searchEnd() = assumeDark().let { render("search-end", scrollToEnd = true) { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "dune") } } }
    @Test fun searchNone() = render("search-none") { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "zzqx the film nobody made") } }
    @Test fun searchLoading() = render("search-loading", catalog = FakeCatalog(Answer.Loading)) { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "dune") } }
    @Test fun searchError() = render("search-error", catalog = FakeCatalog(Answer.Fail)) { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "dune") } }

    @Test fun titleFilm() = render("title-film", history = true, content = phone { open(Dest.Title(Fake.film)) })
    @Test fun titleShow() = render("title-show", history = true, content = phone { open(Dest.Title(Fake.show)) })
    @Test fun titleRotten() = render("title-rotten", content = phone { open(Dest.Title(Fake.rotten)) })
    @Test fun titleShowEpisodes() = render("title-show-episodes", history = true, scrollDp = 700, content = phone { open(Dest.Title(Fake.show)) })
    @Test fun titleReviews() = render("title-reviews", scrollDp = 440, content = phone { open(Dest.Title(Fake.film)) })
    @Test fun reviewOpen() = render("review-open", windows = true) { com.kirtanya.reel.ui.title.ReviewSheet(Fake.reviews.items[0], onDismiss = {}) }
    @Test fun titleBare() = render("title-bare", content = phone { open(Dest.Title(Fake.long)) })
    @Test fun titleLoading() = render("title-loading", catalog = FakeCatalog(Answer.Loading), content = phone { open(Dest.Title(Fake.film)) })
    @Test fun titleError() = render("title-error", catalog = FakeCatalog(Answer.Fail), content = phone { open(Dest.Title(Fake.film)) })
    @Test fun imdbLoading() = render("imdb-loading", catalog = FakeCatalog(Answer.Loading), content = phone { open(Dest.ImdbTitle("tt0137523", com.kirtanya.reel.core.tmdb.MediaType.MOVIE)) })
    @Test fun imdbUnknown() = render("imdb-unknown", catalog = FakeCatalog(Answer.Empty), content = phone { open(Dest.ImdbTitle("tt0137523", com.kirtanya.reel.core.tmdb.MediaType.MOVIE)) })

    @Test fun sourcesSearching() = render("sources-searching", data = RenderData(sourceSearch = { Fake.searching }), content = phone { open(Dest.Sources(Fake.film)) })
    @Test fun sourcesMany() = render("sources-many", data = RenderData(sourceSearch = { Fake.manySources }), content = phone { open(Dest.Sources(Fake.show, 2, 6)) })
    @Test fun sourcesNone() = render("sources-none", data = RenderData(sourceSearch = { Fake.noProviders }), content = phone { open(Dest.Sources(Fake.long)) })
    @Test fun sourcesNothing() = render("sources-nothing", data = RenderData(sourceSearch = { Fake.nothingFound }), content = phone { open(Dest.Sources(Fake.film)) })
    @Test fun sourcesDownload() = render("sources-download", data = RenderData(sourceSearch = { Fake.manySources }), content = phone { open(Dest.Sources(Fake.show, 2, 6, download = true)) })
    @Test fun sourcesFiltered() = render(
        "sources-filtered",
        data = RenderData(sourceSearch = { Fake.manySources }, sourceFilters = com.kirtanya.reel.core.sources.SourceFilters(qualities = setOf(com.kirtanya.reel.core.sources.QualityBucket.UHD), providers = setOf("Torrentio"), sort = com.kirtanya.reel.core.sources.SourceSort.SEEDS)),
        content = phone { open(Dest.Sources(Fake.film)) },
    )
    @Test fun sourcesFilteredOut() = render(
        "sources-filtered-out",
        data = RenderData(sourceSearch = { Fake.manySources }, sourceFilters = com.kirtanya.reel.core.sources.SourceFilters(qualities = setOf(com.kirtanya.reel.core.sources.QualityBucket.HD), types = setOf(com.kirtanya.reel.core.sources.SourceType.SERVICE))),
        content = phone { open(Dest.Sources(Fake.film)) },
    )
    @Test fun sourcesReplace() = render("sources-replace", data = RenderData(sourceSearch = { Fake.manySources }, torrents = Fake.torrents), content = phone { open(Dest.Sources(Fake.film, download = true, replacing = Fake.torrents[0].infoHash)) })
    @Test fun sourcesReplaceConfirm() = render(
        "sources-replace-confirm", windows = true,
        data = RenderData(sourceSearch = { Fake.manySources }, torrents = Fake.torrents, confirmReplace = true),
        content = phone { open(Dest.Sources(Fake.film, download = true, replacing = Fake.torrents[0].infoHash)) },
    )
    @Test fun sourcesFailed() = render("sources-failed", data = RenderData(sourceSearch = { Fake.allFailed }), content = phone { open(Dest.Sources(Fake.film)) })
    /** The list rows on their own (on the page they sit below the fold): every health, type and tag mix. */
    @Test fun sourcesRows() = render("sources-rows") {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            val actions = listOf(com.kirtanya.reel.ui.sources.SourceAction.PLAY, com.kirtanya.reel.ui.sources.SourceAction.DOWNLOAD)
            Fake.sources(7).drop(1).forEachIndexed { i, s ->
                com.kirtanya.reel.ui.sources.SourceRow(com.kirtanya.reel.core.sources.SourceBrowser.view(s), fits = i != 4, action = actions[i % 2], onClick = {})
            }
        }
    }
    /** Trailers and extras in their own section at the end; never the Recommended pick. */
    @Test fun sourcesExtras() = render(
        "sources-extras",
        data = RenderData(sourceSearch = {
            val req = com.kirtanya.reel.core.sources.SourceRequest(Fake.film.id, null, Fake.film.type, "Dune", 2021)
            val trailers = listOf("Dune Official Trailer 4K", "Dune Teaser", "Dune Featurette: The Sandworm").map {
                com.kirtanya.reel.core.sources.SourceExtras.mark(com.kirtanya.reel.core.sources.Source(com.kirtanya.reel.core.sources.SourceKind.DIRECT, it, "Trailers addon", com.kirtanya.reel.core.sources.Quality(2160), url = "https://x/${it.hashCode()}.mp4"), req)
            }
            Fake.manySources.copy(sources = com.kirtanya.reel.core.sources.SourceRanker.rank(trailers + Fake.sources(5)))
        }),
        content = phone { open(Dest.Sources(Fake.film)) },
    )
    /** The user's own tags first on each row: last played, favourite, starred, downloaded. */
    @Test fun sourcesPersonalTags() = render("sources-personal-tags") {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            val personal = listOf(listOf("Last played"), listOf("Favourite", "Starred"), listOf("Downloading 42%"), listOf("Downloaded", "Starred"), listOf("Same as last episode"))
            Fake.sources(6).drop(1).forEachIndexed { i, s ->
                com.kirtanya.reel.ui.sources.SourceRow(com.kirtanya.reel.core.sources.SourceBrowser.view(s, personal[i]), fits = true, action = com.kirtanya.reel.ui.sources.SourceAction.PLAY, onClick = {}, onLongClick = {})
            }
        }
    }
    /** Long-press menu: play, download, stream only, favourite, star, share. */
    @Test fun sourceOptions() = render("source-options", windows = true) {
        val s = Fake.sources(3)[1]
        val req = com.kirtanya.reel.core.sources.SourceRequest(Fake.film.id, null, Fake.film.type, "Dune", 2021)
        com.kirtanya.reel.ui.sources.SourceOptionsDialog(
            com.kirtanya.reel.core.sources.SourceBrowser.view(s, listOf("Last played")), req,
            com.kirtanya.reel.core.library.SourceMark(req.key, "movie:${Fake.film.id}", s.key, starred = true),
            "Dune", onPlay = {}, onDownload = {}, onDismiss = {},
        )
    }
    @Test fun sourcesStarting() = render("sources-starting", data = RenderData(sourceSearch = { null }), content = phone { open(Dest.Sources(Fake.film)) })

    @Test fun castStarting() = render("cast-starting", data = RenderData(sourceSearch = { Fake.searching }), content = phone { open(Dest.Cast(castFilm)) })
    @Test fun castNone() = render("cast-none", data = RenderData(sourceSearch = { Fake.nothingFound }), content = phone { open(Dest.Cast(castFilm)) })

    @Test fun collectionService() = render("collection-service", content = phone { open(Dest.Collection("Netflix", provider = Provider.FEATURED[0])) })
    @Test fun collectionRow() = render("collection-row", content = phone { open(Dest.Collection("Top rated shows", items = Fake.titles(1, seed = 20))) })
    @Test fun collectionError() = render("collection-error", catalog = FakeCatalog(Answer.Fail), content = phone { open(Dest.Collection("JioHotstar", provider = Provider.FEATURED[2])) })

    private val scifi = com.kirtanya.reel.core.tmdb.Genre(878, "Science Fiction")
    @Test fun collectionGenre() = render("collection-genre", content = phone { open(Dest.Collection(scifi.name, genre = scifi, genreType = com.kirtanya.reel.core.tmdb.MediaType.MOVIE)) })
    @Test fun collectionGenreLoading() = render("collection-genre-loading", catalog = FakeCatalog(Answer.Loading), content = phone { open(Dest.Collection(scifi.name, genre = scifi, genreType = com.kirtanya.reel.core.tmdb.MediaType.MOVIE)) })
    @Test fun person() = render("person", content = phone { open(Dest.Person(1, "Timothée Chalamet")) })
    @Test fun personError() = render("person-error", catalog = FakeCatalog(Answer.Fail), content = phone { open(Dest.Person(1, "Timothée Chalamet")) })

    @Test fun downloads() = render("downloads", data = RenderData(torrents = Fake.torrents, storage = 3_400_000_000L to 21_800_000_000L, origins = Fake.origins), content = phone { select(Tab.Downloads) })
    @Test fun downloadsEnd() = render("downloads-end", scrollToEnd = true, data = RenderData(torrents = Fake.torrents, storage = 3_400_000_000L to 21_800_000_000L, origins = Fake.origins), content = phone { select(Tab.Downloads) })
    @Test fun downloadsCache() = render("downloads-cache", data = RenderData(torrents = Fake.torrents.takeLast(3), storage = 3_400_000_000L to 21_800_000_000L, origins = Fake.origins), content = phone { select(Tab.Downloads) })
    /** Alone over the canvas: the design rules read a dialog's own window. */
    @Test fun downloadsMore() = render("downloads-more", windows = true) { com.kirtanya.reel.ui.MoreDialog(Fake.torrents[0], canChangeSource = true, {}, {}, {}, onGoToTitle = {}) }
    // Long-press options (Continue watching, posters, episodes) and the Undo after removing a card.
    @Test fun continueOptions() = render("continue-options", history = true, windows = true) { ContinueOptionsScene(show = false) }
    @Test fun continueOptionsShow() = render("continue-options-show", history = true, windows = true) { ContinueOptionsScene(show = true) }
    @Test fun posterOptions() = render("poster-options", windows = true) { PosterOptionsScene() }
    @Test fun episodeOptions() = render("episode-options", history = true, windows = true) { EpisodeOptionsScene() }
    @Test fun episodeOptionsUnplayed() = render("episode-options-unplayed", windows = true) { EpisodeOptionsScene() }
    @Test fun continueUndo() = render("continue-undo", history = true) { UndoScene { phone()() } }

    @Test fun downloadsNight() = render("downloads-night", data = RenderData(torrents = nightTorrents, storage = 3_400_000_000L to 21_800_000_000L, origins = Fake.origins), content = phone { select(Tab.Downloads) })
    @Test fun downloadsEmpty() = render("downloads-empty", data = RenderData(torrents = emptyList(), storage = 0L to 51_000_000_000L), content = phone { select(Tab.Downloads) })

    @Test fun settings() = render("settings", content = phone { select(Tab.Settings) })
    // Home screen, History, Source preferences (Settings), a rearranged Home in wide rows.
    @Test fun settingsHomeLayout() {
        com.kirtanya.reel.ui.home.HomeLayout.init(app); com.kirtanya.reel.ui.home.HomeLayout.reset()
        com.kirtanya.reel.ui.home.HomeLayout.setShown(com.kirtanya.reel.ui.home.RowIds.SERVICES, false)
        try { settingsPage(SettingsPage.HomeLayout) } finally { com.kirtanya.reel.ui.home.HomeLayout.reset() }
    }
    @Test fun settingsHistory() {
        app.hidden.hide(Fake.titles(1, seed = 3).first())
        try { render("settings-history", history = true, data = RenderData(installed = Fake.installed)) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.History)) } } }
        finally { Fake.titles(1, seed = 3).first().let { app.hidden.unhide(it.ref) } }
    }
    @Test fun settingsSourcePrefs() = settingsPage(SettingsPage.SourcePrefs)
    @Test fun homeWide() {
        com.kirtanya.reel.ui.home.HomeLayout.init(app); com.kirtanya.reel.ui.home.HomeLayout.reset()
        com.kirtanya.reel.ui.home.HomeLayout.choosePosterStyle(com.kirtanya.reel.ui.home.PosterStyle.Wide)
        com.kirtanya.reel.ui.home.HomeLayout.chooseHero(com.kirtanya.reel.ui.home.HeroSource.MyList)
        try { render("home-wide", history = true, content = phone()) } finally { com.kirtanya.reel.ui.home.HomeLayout.reset() }
    }
    @Test fun settingsEnd() = render("settings-end", scrollToEnd = true, content = phone { select(Tab.Settings) })
    private fun settingsPage(page: SettingsPage, data: RenderData? = RenderData(installed = Fake.installed, store = Fake.store)) =
        render("settings-${page.name.lowercase()}", data = data) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(page)) } }
    @Test fun settingsAppearance() = settingsPage(SettingsPage.Appearance)
    @Test fun settingsPlayback() = settingsPage(SettingsPage.Playback)
    @Test fun settingsSubtitles() = settingsPage(SettingsPage.Subtitles)
    @Test fun settingsAddons() = settingsPage(SettingsPage.Addons)
    @Test fun settingsAddonsEmpty() = render("settings-addons-empty", data = RenderData(installed = com.kirtanya.reel.data.SourcesState())) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.Addons)) } }
    @Test fun settingsStore() = settingsPage(SettingsPage.Store)
    @Test fun settingsStoreFailed() = render("settings-store-failed", data = RenderData(installed = Fake.installed, store = com.kirtanya.reel.data.StoreState(Fake.store.catalogs, failed = listOf("Stremio official")))) {
        PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.Store)) }
    }
    @Test fun settingsStoreFiltered() = render(
        "settings-store-filtered",
        data = RenderData(installed = Fake.installed, store = Fake.store, storeQuery = com.kirtanya.reel.core.store.StoreQuery(kinds = setOf(com.kirtanya.reel.core.store.StoreKind.STREAMS), status = setOf(com.kirtanya.reel.core.store.StoreStatus.NOT_ADDED))),
    ) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.Store)) } }
    @Test fun settingsStoreNoMatch() = render(
        "settings-store-no-match",
        data = RenderData(installed = Fake.installed, store = Fake.store, storeQuery = com.kirtanya.reel.core.store.StoreQuery("zzz", types = setOf("tv"))),
    ) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.Store)) } }
    @Test fun settingsPlugins() = settingsPage(SettingsPage.Plugins)
    @Test fun settingsPhoneTv() = settingsPage(SettingsPage.PhoneTv)
    @Test fun settingsStorage() = settingsPage(SettingsPage.Storage)
    // Download rules (STREAMING_V2 §8.3): the overnight window on, so its hour rows show, and the cap while watching.
    @Test fun settingsStorageRules() = withNight {
        render("settings-storage-rules", scrollToEnd = true, data = RenderData(installed = Fake.installed, store = Fake.store)) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.Storage)) } }
    }
    private fun withNight(block: () -> Unit) {
        val rules = com.kirtanya.reel.engine.DownloadRulesStore.get(app)
        rules.update { it.copy(night = com.kirtanya.reel.core.stream.NightWindow(enabled = true)) }
        try { block() } finally { rules.update { com.kirtanya.reel.core.stream.DownloadRules() } }
    }
    /** One download slowed while something plays, two waiting for the overnight window (with Start now). */
    private val nightTorrents get() = Fake.torrents.take(3).mapIndexed { i, t ->
        if (i == 0) t.copy(notice = "Slowed while you watch")
        else t.copy(phase = com.kirtanya.reel.engine.TorrentPhase.PAUSED, waitingForNight = true, notice = "Waiting for night (01:00)")
    }
    @Test fun settingsAbout() = settingsPage(SettingsPage.About)

    // ─── Home disk (docs/HOME_DISK.md §17): set up now, no disk yet ─────────────────────────────

    private fun homePage(name: String, home: com.kirtanya.reel.ui.homedisk.HomeView) =
        render(name, data = RenderData(installed = Fake.installed, home = home)) { PhoneTab(Tab.Settings) { SettingsScreen(isTv = false, start = listOf(SettingsPage.HomeStorage)) } }
    @Test fun settingsHomeStorage() = homePage("settings-homestorage", FakeHome.notConnected)
    @Test fun settingsHomeStorageNotSetUp() = homePage("settings-homestorage-not-set-up", FakeHome.notSetUp)
    @Test fun settingsHomeStorageNewWifi() = homePage("settings-homestorage-new-wifi", FakeHome.newWifi)
    @Test fun downloadWhere() = render("download-where", windows = true, data = RenderData(home = FakeHome.newWifi)) {
        com.kirtanya.reel.ui.homedisk.DownloadWhereSheetView(pickOnly = false, onPick = { _, _ -> }, onDismiss = {})
    }
    @Test fun downloadWhereNotSetUp() = render("download-where-not-set-up", windows = true, data = RenderData(home = FakeHome.notSetUp)) {
        com.kirtanya.reel.ui.homedisk.DownloadWhereSheetView(pickOnly = false, onPick = { _, _ -> }, onDismiss = {})
    }
    @Test fun downloadWhereChange() = render("download-where-change", windows = true, data = RenderData(home = FakeHome.notConnected)) {
        com.kirtanya.reel.ui.homedisk.DownloadWhereSheetView(pickOnly = true, onPick = { _, _ -> }, onDismiss = {})
    }
    @Test fun downloadsHome() = render("downloads-home", data = RenderData(torrents = Fake.torrents, storage = 3_400_000_000L to 21_800_000_000L, origins = Fake.origins, home = FakeHome.newWifi), content = phone { select(Tab.Downloads) })
    @Test fun downloadsMobile() = render("downloads-mobile", data = RenderData(torrents = Fake.torrents.take(2), storage = 3_400_000_000L to 21_800_000_000L, origins = Fake.origins, home = FakeHome.mobile), content = phone { select(Tab.Downloads) })
    @Test fun downloadsDeleteWhere() = render("downloads-delete-where", windows = true) {
        com.kirtanya.reel.ui.homedisk.DeleteWhereDialog(
            "Sintel", com.kirtanya.reel.core.homedisk.DeleteWhere.choice(onPhone = true, disk = com.kirtanya.reel.core.homedisk.DiskPresence.UNKNOWN), {}, {},
        )
    }
    @Test fun networkAsk() = render("network-ask", windows = true) {
        com.kirtanya.reel.ui.homedisk.NetworkPrompt(
            com.kirtanya.reel.homedisk.GatePrompt(com.kirtanya.reel.core.homedisk.P2pVerdict.Ask("x"), com.kirtanya.reel.core.homedisk.P2pUse.STREAM, proceed = {}, atHome = {}), {}, {},
        )
    }
    @Test fun networkWork() = render("network-work", windows = true) {
        com.kirtanya.reel.ui.homedisk.NetworkPrompt(
            com.kirtanya.reel.homedisk.GatePrompt(com.kirtanya.reel.core.homedisk.P2pVerdict.Block(com.kirtanya.reel.core.homedisk.WaitReason.WORK_NETWORK), com.kirtanya.reel.core.homedisk.P2pUse.STREAM, proceed = {}, atHome = {}), {}, {},
        )
    }
    @Test fun networkMobile() = render("network-mobile", windows = true) {
        com.kirtanya.reel.ui.homedisk.NetworkPrompt(
            com.kirtanya.reel.homedisk.GatePrompt(com.kirtanya.reel.core.homedisk.P2pVerdict.Wait(com.kirtanya.reel.core.homedisk.WaitReason.MOBILE_DATA, true), com.kirtanya.reel.core.homedisk.P2pUse.DOWNLOAD, proceed = {}, atHome = {}, later = {}), {}, {},
        )
    }

    @Test fun addLink() = render("add-link", windows = true) {
        Box(Modifier.fillMaxSize()) { AddLinkSheet(initialLink = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny+(2008)+[1080p]+[Blender+Open+Movie]", onDismiss = {}, onDownloadStarted = {}) }
    }
    @Test fun addLinkInvalid() = render("add-link-invalid", windows = true) {
        Box(Modifier.fillMaxSize()) { AddLinkSheet(initialLink = "https://example.org/not-a-magnet", onDismiss = {}, onDownloadStarted = {}) }
    }
    private val preview = SourcesStore.LinkPreview(
        "https://mediafusion.elfhosted.com/eyJlbmFibGVkX3Byb3ZpZGVycyI6WyJyZCJdfQ/manifest.json", LinkKind.ADDON,
        "MediaFusion | ElfHosted 🇮🇳 Hindi · Tamil · Telugu",
        "Stremio addon · streams for films and shows\nUniversal addon for Indian movies, series, live TV and sports with debrid support.", "{}",
    )
    @Test fun addSource() = render("add-source", windows = true, data = RenderData(linkPreview = Load.Ready(preview))) {
        AddSourceLinkDialog(preview.url, isTv = false, onDone = {})
    }
    @Test fun addSourceLoading() = render("add-source-loading", windows = true, data = RenderData(linkPreview = Load.Loading)) {
        AddSourceLinkDialog(preview.url, isTv = false, onDone = {})
    }
    @Test fun addSourceFailed() = render("add-source-failed", windows = true, data = RenderData(linkPreview = Load.Failed("That link isn't a Stremio addon or a plugin repository (HTTP 404 from mediafusion.elfhosted.com)"))) {
        AddSourceLinkDialog(preview.url, isTv = false, onDone = {})
    }

    // ─── TV (dark, font scale 1.0) ─────────────────────────────────────────────────────────

    @Test @Config(qualifiers = TV) fun tvHome() = render("home", tv = true, history = true, content = tvApp())
    @Test @Config(qualifiers = TV) fun tvHomeArtMatched() = render("home-art-matched", tv = true, history = true, artMatched = true, content = tvApp())
    @Test @Config(qualifiers = TV) fun tvHomeFirstRun() = render("home-first-run", tv = true, content = tvApp())
    @Test @Config(qualifiers = TV) fun tvHomeLoading() = render("home-loading", tv = true, catalog = FakeCatalog(Answer.Loading), content = tvApp())
    @Test @Config(qualifiers = TV) fun tvHomeError() = render("home-error", tv = true, catalog = FakeCatalog(Answer.Fail), content = tvApp())
    @Test @Config(qualifiers = TV) fun tvSearch() = render("search", tv = true, content = tvApp { select(Tab.Search) })
    @Test @Config(qualifiers = TV) fun tvSearchResults() = render("search-results", tv = true) { TvTab(Tab.Search, padTop = false) { TvSearch(onOpen = {}, initialQuery = "dune") } }
    @Test @Config(qualifiers = TV) fun tvSearchNone() = render("search-none", tv = true) { TvTab(Tab.Search, padTop = false) { TvSearch(onOpen = {}, initialQuery = "zzqx the film nobody made") } }
    @Test @Config(qualifiers = TV) fun tvTitleFilm() = render("title-film", tv = true, history = true, content = tvApp { open(Dest.Title(Fake.film)) })
    @Test @Config(qualifiers = TV) fun tvTitleRotten() = render("title-rotten", tv = true, content = tvApp { open(Dest.Title(Fake.rotten)) })
    @Test @Config(qualifiers = TV) fun tvTitleReviews() = render("title-reviews", tv = true, focusDown = 1, content = tvApp { open(Dest.Title(Fake.film)) })
    @Test @Config(qualifiers = TV) fun tvTitleShowEpisodes() = render("title-show-episodes", tv = true, focusDown = 2, content = tvApp { open(Dest.Title(Fake.show)) })
    @Test @Config(qualifiers = TV) fun tvReviewOpen() = render("review-open", tv = true, windows = true) { TvScene { com.kirtanya.reel.ui.title.ReviewSheet(Fake.reviews.items[0], onDismiss = {}) } }
    @Test @Config(qualifiers = TV) fun tvTitleShow() = render("title-show", tv = true, history = true, content = tvApp { open(Dest.Title(Fake.show)) })
    @Test @Config(qualifiers = TV) fun tvImdbUnknown() = render("imdb-unknown", tv = true, catalog = FakeCatalog(Answer.Empty), content = tvApp { open(Dest.ImdbTitle("tt0944947", com.kirtanya.reel.core.tmdb.MediaType.TV)) })
    @Test @Config(qualifiers = TV) fun tvTitleBare() = render("title-bare", tv = true, content = tvApp { open(Dest.Title(Fake.long)) })
    @Test @Config(qualifiers = TV) fun tvSourcesMany() = render("sources-many", tv = true, data = RenderData(sourceSearch = { Fake.manySources }), content = tvApp { open(Dest.Sources(Fake.show, 2, 6)) })
    @Test @Config(qualifiers = TV) fun tvSourcesSearching() = render("sources-searching", tv = true, data = RenderData(sourceSearch = { Fake.searching }), content = tvApp { open(Dest.Sources(Fake.film)) })
    @Test @Config(qualifiers = TV) fun tvSourcesFiltered() = render(
        "sources-filtered", tv = true,
        data = RenderData(sourceSearch = { Fake.manySources }, sourceFilters = com.kirtanya.reel.core.sources.SourceFilters(qualities = setOf(com.kirtanya.reel.core.sources.QualityBucket.FHD))),
        content = tvApp { open(Dest.Sources(Fake.film)) },
    )
    @Test @Config(qualifiers = TV) fun tvSourcesReplace() = render("sources-replace", tv = true, data = RenderData(sourceSearch = { Fake.manySources }, torrents = Fake.torrents), content = tvApp { open(Dest.Sources(Fake.film, download = true, replacing = Fake.torrents[0].infoHash)) })
    @Test @Config(qualifiers = TV) fun tvSourcesNone() = render("sources-none", tv = true, data = RenderData(sourceSearch = { Fake.noProviders }), content = tvApp { open(Dest.Sources(Fake.long)) })
    @Test @Config(qualifiers = TV) fun tvCastStarting() = render("cast-starting", tv = true, data = RenderData(sourceSearch = { Fake.searching }), content = tvApp { open(Dest.Cast(castShow)) })
    @Test @Config(qualifiers = TV) fun tvCastNone() = render("cast-none", tv = true, data = RenderData(sourceSearch = { Fake.nothingFound }), content = tvApp { open(Dest.Cast(castFilm)) })
    @Test @Config(qualifiers = TV) fun tvCollection() = render("collection-service", tv = true, content = tvApp { open(Dest.Collection("Netflix", provider = Provider.FEATURED[0])) })
    @Test @Config(qualifiers = TV) fun tvCollectionGenre() = render("collection-genre", tv = true, content = tvApp { open(Dest.Collection("Drama", genre = com.kirtanya.reel.core.tmdb.Genre(18, "Drama"), genreType = com.kirtanya.reel.core.tmdb.MediaType.TV)) })
    @Test @Config(qualifiers = TV) fun tvPerson() = render("person", tv = true, content = tvApp { open(Dest.Person(1, "Timothée Chalamet")) })
    @Test @Config(qualifiers = TV) fun tvDownloads() = render("downloads", tv = true, data = RenderData(torrents = Fake.torrents, storage = 3_400_000_000L to 9_800_000_000L, origins = Fake.origins), content = tvApp { select(Tab.Downloads) })
    @Test @Config(qualifiers = TV) fun tvDownloadsCache() = render("downloads-cache", tv = true, data = RenderData(torrents = Fake.torrents.takeLast(3), storage = 3_400_000_000L to 9_800_000_000L, origins = Fake.origins), content = tvApp { select(Tab.Downloads) })
    @Test @Config(qualifiers = TV) fun tvDownloadsMore() = render("downloads-more", tv = true, windows = true) { com.kirtanya.reel.ui.MoreDialog(Fake.torrents[0], canChangeSource = true, {}, {}, {}, onGoToTitle = {}) }
    @Test @Config(qualifiers = TV) fun tvContinueOptions() = render("continue-options", tv = true, history = true, windows = true) { TvScene { ContinueOptionsScene(show = false) } }
    @Test @Config(qualifiers = TV) fun tvPosterOptions() = render("poster-options", tv = true, windows = true) { TvScene { PosterOptionsScene() } }
    @Test @Config(qualifiers = TV) fun tvEpisodeOptions() = render("episode-options", tv = true, history = true, windows = true) { TvScene { EpisodeOptionsScene() } }
    @Test @Config(qualifiers = TV) fun tvContinueUndo() = render("continue-undo", tv = true, history = true) { UndoScene { tvApp()() } }
    @Test @Config(qualifiers = TV) fun tvDownloadsEmpty() = render("downloads-empty", tv = true, data = RenderData(torrents = emptyList(), storage = 0L to 9_800_000_000L), content = tvApp { select(Tab.Downloads) })
    @Test @Config(qualifiers = TV) fun tvSettings() = render("settings", tv = true, content = tvApp { select(Tab.Settings) })
    @Test @Config(qualifiers = TV) fun tvSettingsHomeLayout() = render("settings-homelayout", tv = true) {
        TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(SettingsPage.HomeLayout)) }
    }
    @Test @Config(qualifiers = TV) fun tvScreensaver() {
        com.kirtanya.reel.ui.home.HomeLayout.init(app)
        render("screensaver", tv = true, history = true) {
            val w = androidx.compose.runtime.remember { com.kirtanya.reel.ui.tv.IdleWatch().apply { dreaming = true } }
            com.kirtanya.reel.ui.tv.TvScreensaver(w, enabled = false)
        }
    }
    private fun tvSettingsPage(page: SettingsPage) =
        render("settings-${page.name.lowercase()}", tv = true, data = RenderData(installed = Fake.installed, store = Fake.store)) { TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(page)) } }
    @Test @Config(qualifiers = TV) fun tvSettingsAppearance() = tvSettingsPage(SettingsPage.Appearance)
    @Test @Config(qualifiers = TV) fun tvSettingsAddons() = tvSettingsPage(SettingsPage.Addons)
    @Test @Config(qualifiers = TV) fun tvSettingsSubtitles() = tvSettingsPage(SettingsPage.Subtitles)
    @Test @Config(qualifiers = TV) fun tvSettingsStore() = tvSettingsPage(SettingsPage.Store)
    @Test @Config(qualifiers = TV) fun tvSettingsStoreFiltered() = render(
        "settings-store-filtered", tv = true,
        data = RenderData(installed = Fake.installed, store = Fake.store, storeQuery = com.kirtanya.reel.core.store.StoreQuery(types = setOf("series"))),
    ) { TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(SettingsPage.Store)) } }
    @Test @Config(qualifiers = TV) fun tvSettingsPhoneTv() = tvSettingsPage(SettingsPage.PhoneTv)
    @Test @Config(qualifiers = TV) fun tvSettingsStorageRules() = withNight {
        render("settings-storage-rules", tv = true, scrollToEnd = true, data = RenderData(installed = Fake.installed, store = Fake.store)) { TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(SettingsPage.Storage)) } }
    }
    @Test @Config(qualifiers = TV) fun tvDownloadsNight() = render("downloads-night", tv = true, data = RenderData(torrents = nightTorrents, storage = 0L to 9_800_000_000L), content = tvApp { select(Tab.Downloads) })
    @Test @Config(qualifiers = TV) fun tvSettingsHomeStorage() =
        render("settings-homestorage", tv = true, data = RenderData(installed = Fake.installed, home = FakeHome.tv)) { TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(SettingsPage.HomeStorage)) } }
    @Test @Config(qualifiers = TV) fun tvSettingsHomeStorageNotSetUp() =
        render("settings-homestorage-not-set-up", tv = true, data = RenderData(installed = Fake.installed, home = FakeHome.notSetUp.copy(isTv = true))) { TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(SettingsPage.HomeStorage)) } }
    @Test @Config(qualifiers = TV) fun tvAddSource() = render("add-source", tv = true, windows = true, data = RenderData(linkPreview = Load.Ready(preview))) {
        AddSourceLinkDialog(preview.url, isTv = true, onDone = {})
    }

    // ─── Option sheets (shown as if long-pressed) ─────────────────────────────────────────────

    /** The TV root's locals: remote input, sheets as a side panel. */
    @Composable
    private fun TvScene(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            com.kirtanya.reel.ui.common.LocalIsTv provides true,
        ) { content() }
    }

    /** The sheet for the seeded film (Resume: every option) or show (Up next). */
    @Composable
    private fun ContinueOptionsScene(show: Boolean) {
        val items = com.kirtanya.reel.ui.common.rememberContinueWatching()
        val item = items.firstOrNull { it.entry.playback.tmdbType == if (show) "tv" else "movie" } ?: return
        com.kirtanya.reel.ui.common.ContinueOptions(item, onOpenDest = {}, onDismiss = {}, refocus = {})
    }

    @Composable
    private fun PosterOptionsScene() {
        com.kirtanya.reel.ui.common.PosterOptions(Fake.details(Fake.film).summary, onOpen = {}, onDismiss = {})
    }

    /** The show's S2 E5: finished with history (Mark unwatched), never played without it (Mark watched). */
    @Composable
    private fun EpisodeOptionsScene() {
        val history = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ReelApp).history
        val e = Fake.episodes(2, 24)[4]
        val watched = history.entries.value.firstOrNull { it.playback.episode == com.kirtanya.reel.core.playback.EpisodeNumber(2, 5) }
        com.kirtanya.reel.ui.title.EpisodeOptions(Fake.details(Fake.show), e, watched, onPlay = { _, _ -> }, onOpenDest = {}, onDismiss = {})
    }

    /** Home right after "Remove from row": the card is gone and the app's Undo snackbar is up. */
    @Composable
    private fun UndoScene(app: @Composable () -> Unit) {
        val undo = com.kirtanya.reel.ui.common.rememberUndoHost()
        androidx.compose.runtime.LaunchedEffect(Unit) { undo.remove("movie:${Fake.FILM_ID}", "Removed Dune: Part Two", commit = {}) }
        CompositionLocalProvider(com.kirtanya.reel.ui.common.LocalUndo provides undo) { app() }
    }

    // ─── Brand ───────────────────────────────────────────────────────────────────────────────

    /** Google TV launcher banner (320 × 180 dp): mark + name. Copied to res/drawable-xhdpi/banner.png. */
    @Test @Config(qualifiers = "w320dp-h180dp-land-night-xhdpi")
    fun brandBanner() {
        assumeTrue(variant == DEFAULT && rendersBrand)
        compose.setContent {
            ReelTheme {
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_bg), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.kirtanya.reel.ui.common.BrandMark(72.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 16.dp))
                        androidx.compose.material3.Text("One Stream", style = Tokens.Type.hero.copy(fontSize = 34.sp, lineHeight = 38.sp), color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screens/brand-banner.png")
    }

    /** The logo at 512 px, for docs. */
    @Test @Config(qualifiers = "w256dp-h256dp-night-xhdpi")
    fun brandLogo() {
        assumeTrue(variant == DEFAULT && rendersBrand)
        compose.setContent { com.kirtanya.reel.ui.common.BrandMark(256.dp) }
        compose.onRoot().captureRoboImage("build/screens/brand-logo.png")
    }

    private val castFilm get() = com.kirtanya.reel.core.launcher.CastRequest(false, Fake.film, null, null, com.kirtanya.reel.core.launcher.CastHint())
    private val castShow get() = com.kirtanya.reel.core.launcher.CastRequest(false, Fake.show, 2, 6, com.kirtanya.reel.core.launcher.CastHint())

    companion object {
        /** Pixel 8 as the user has it: 1080 × 2400 px, display size raised to 480 dpi → 360 × 800 dp. */
        const val PHONE = "w360dp-h800dp-port-notnight-xxhdpi"
        /** Acer R4 Google TV: 1920 × 1080 px at 320 dpi. */
        const val TV = "w960dp-h540dp-land-television-night-xhdpi"

        val DEFAULT = Variant(dark = true, fontScale = 1f)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(
            DEFAULT, Variant(dark = false, fontScale = 1f), Variant(dark = true, fontScale = 1.3f), Variant(dark = false, fontScale = 1.3f),
        ).map { arrayOf(it) }
    }
}
