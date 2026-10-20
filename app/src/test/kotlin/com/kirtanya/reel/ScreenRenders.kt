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
        /** Dialogs and sheets are windows of their own: capture the whole screen. */
        windows: Boolean = false,
        waitMs: Long = 1_800,
        content: @Composable () -> Unit,
    ) {
        if (tv) assumeTrue("TV renders run once", variant == DEFAULT)
        AccentState.chooseTheme(if (tv || variant.dark) ThemeMode.Dark else ThemeMode.Light)
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).components { add(FakeArt) }.build() }
        Fake.clearHistory(app)
        if (history) Fake.seedHistory(app)
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
    @Test fun searchNone() = render("search-none") { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "zzqx the film nobody made") } }
    @Test fun searchLoading() = render("search-loading", catalog = FakeCatalog(Answer.Loading)) { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "dune") } }
    @Test fun searchError() = render("search-error", catalog = FakeCatalog(Answer.Fail)) { PhoneTab(Tab.Search) { PhoneSearch(onOpen = {}, initialQuery = "dune") } }

    @Test fun titleFilm() = render("title-film", history = true, content = phone { open(Dest.Title(Fake.film)) })
    @Test fun titleShow() = render("title-show", history = true, content = phone { open(Dest.Title(Fake.show)) })
    @Test fun titleBare() = render("title-bare", content = phone { open(Dest.Title(Fake.long)) })
    @Test fun titleLoading() = render("title-loading", catalog = FakeCatalog(Answer.Loading), content = phone { open(Dest.Title(Fake.film)) })
    @Test fun titleError() = render("title-error", catalog = FakeCatalog(Answer.Fail), content = phone { open(Dest.Title(Fake.film)) })

    @Test fun sourcesSearching() = render("sources-searching", data = RenderData(sourceSearch = { Fake.searching }), content = phone { open(Dest.Sources(Fake.film)) })
    @Test fun sourcesMany() = render("sources-many", data = RenderData(sourceSearch = { Fake.manySources }), content = phone { open(Dest.Sources(Fake.show, 2, 6)) })
    @Test fun sourcesNone() = render("sources-none", data = RenderData(sourceSearch = { Fake.noProviders }), content = phone { open(Dest.Sources(Fake.long)) })
    @Test fun sourcesNothing() = render("sources-nothing", data = RenderData(sourceSearch = { Fake.nothingFound }), content = phone { open(Dest.Sources(Fake.film)) })
    @Test fun sourcesStarting() = render("sources-starting", data = RenderData(sourceSearch = { null }), content = phone { open(Dest.Sources(Fake.film)) })

    @Test fun collectionService() = render("collection-service", content = phone { open(Dest.Collection("Netflix", provider = Provider.FEATURED[0])) })
    @Test fun collectionRow() = render("collection-row", content = phone { open(Dest.Collection("Top rated shows", items = Fake.titles(1, seed = 20))) })
    @Test fun collectionError() = render("collection-error", catalog = FakeCatalog(Answer.Fail), content = phone { open(Dest.Collection("JioHotstar", provider = Provider.FEATURED[2])) })

    private val scifi = com.kirtanya.reel.core.tmdb.Genre(878, "Science Fiction")
    @Test fun collectionGenre() = render("collection-genre", content = phone { open(Dest.Collection(scifi.name, genre = scifi, genreType = com.kirtanya.reel.core.tmdb.MediaType.MOVIE)) })
    @Test fun collectionGenreLoading() = render("collection-genre-loading", catalog = FakeCatalog(Answer.Loading), content = phone { open(Dest.Collection(scifi.name, genre = scifi, genreType = com.kirtanya.reel.core.tmdb.MediaType.MOVIE)) })
    @Test fun person() = render("person", content = phone { open(Dest.Person(1, "Timothée Chalamet")) })
    @Test fun personError() = render("person-error", catalog = FakeCatalog(Answer.Fail), content = phone { open(Dest.Person(1, "Timothée Chalamet")) })

    @Test fun downloads() = render("downloads", data = RenderData(torrents = Fake.torrents, storage = 3_400_000_000L to 21_800_000_000L), content = phone { select(Tab.Downloads) })
    @Test fun downloadsEmpty() = render("downloads-empty", data = RenderData(torrents = emptyList(), storage = 0L to 51_000_000_000L), content = phone { select(Tab.Downloads) })

    @Test fun settings() = render("settings", content = phone { select(Tab.Settings) })
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
    @Test fun settingsPlugins() = settingsPage(SettingsPage.Plugins)
    @Test fun settingsPhoneTv() = settingsPage(SettingsPage.PhoneTv)
    @Test fun settingsStorage() = settingsPage(SettingsPage.Storage)
    @Test fun settingsAbout() = settingsPage(SettingsPage.About)

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
    @Test @Config(qualifiers = TV) fun tvHomeFirstRun() = render("home-first-run", tv = true, content = tvApp())
    @Test @Config(qualifiers = TV) fun tvHomeLoading() = render("home-loading", tv = true, catalog = FakeCatalog(Answer.Loading), content = tvApp())
    @Test @Config(qualifiers = TV) fun tvHomeError() = render("home-error", tv = true, catalog = FakeCatalog(Answer.Fail), content = tvApp())
    @Test @Config(qualifiers = TV) fun tvSearch() = render("search", tv = true, content = tvApp { select(Tab.Search) })
    @Test @Config(qualifiers = TV) fun tvSearchResults() = render("search-results", tv = true) { TvTab(Tab.Search, padTop = false) { TvSearch(onOpen = {}, initialQuery = "dune") } }
    @Test @Config(qualifiers = TV) fun tvSearchNone() = render("search-none", tv = true) { TvTab(Tab.Search, padTop = false) { TvSearch(onOpen = {}, initialQuery = "zzqx the film nobody made") } }
    @Test @Config(qualifiers = TV) fun tvTitleFilm() = render("title-film", tv = true, history = true, content = tvApp { open(Dest.Title(Fake.film)) })
    @Test @Config(qualifiers = TV) fun tvTitleShow() = render("title-show", tv = true, history = true, content = tvApp { open(Dest.Title(Fake.show)) })
    @Test @Config(qualifiers = TV) fun tvTitleBare() = render("title-bare", tv = true, content = tvApp { open(Dest.Title(Fake.long)) })
    @Test @Config(qualifiers = TV) fun tvSourcesMany() = render("sources-many", tv = true, data = RenderData(sourceSearch = { Fake.manySources }), content = tvApp { open(Dest.Sources(Fake.show, 2, 6)) })
    @Test @Config(qualifiers = TV) fun tvSourcesSearching() = render("sources-searching", tv = true, data = RenderData(sourceSearch = { Fake.searching }), content = tvApp { open(Dest.Sources(Fake.film)) })
    @Test @Config(qualifiers = TV) fun tvSourcesNone() = render("sources-none", tv = true, data = RenderData(sourceSearch = { Fake.noProviders }), content = tvApp { open(Dest.Sources(Fake.long)) })
    @Test @Config(qualifiers = TV) fun tvCollection() = render("collection-service", tv = true, content = tvApp { open(Dest.Collection("Netflix", provider = Provider.FEATURED[0])) })
    @Test @Config(qualifiers = TV) fun tvCollectionGenre() = render("collection-genre", tv = true, content = tvApp { open(Dest.Collection("Drama", genre = com.kirtanya.reel.core.tmdb.Genre(18, "Drama"), genreType = com.kirtanya.reel.core.tmdb.MediaType.TV)) })
    @Test @Config(qualifiers = TV) fun tvPerson() = render("person", tv = true, content = tvApp { open(Dest.Person(1, "Timothée Chalamet")) })
    @Test @Config(qualifiers = TV) fun tvDownloads() = render("downloads", tv = true, data = RenderData(torrents = Fake.torrents, storage = 3_400_000_000L to 9_800_000_000L), content = tvApp { select(Tab.Downloads) })
    @Test @Config(qualifiers = TV) fun tvDownloadsEmpty() = render("downloads-empty", tv = true, data = RenderData(torrents = emptyList(), storage = 0L to 9_800_000_000L), content = tvApp { select(Tab.Downloads) })
    @Test @Config(qualifiers = TV) fun tvSettings() = render("settings", tv = true, content = tvApp { select(Tab.Settings) })
    private fun tvSettingsPage(page: SettingsPage) =
        render("settings-${page.name.lowercase()}", tv = true, data = RenderData(installed = Fake.installed, store = Fake.store)) { TvTab(Tab.Settings, padTop = true) { SettingsScreen(isTv = true, start = listOf(page)) } }
    @Test @Config(qualifiers = TV) fun tvSettingsAppearance() = tvSettingsPage(SettingsPage.Appearance)
    @Test @Config(qualifiers = TV) fun tvSettingsAddons() = tvSettingsPage(SettingsPage.Addons)
    @Test @Config(qualifiers = TV) fun tvSettingsStore() = tvSettingsPage(SettingsPage.Store)
    @Test @Config(qualifiers = TV) fun tvSettingsPhoneTv() = tvSettingsPage(SettingsPage.PhoneTv)
    @Test @Config(qualifiers = TV) fun tvAddSource() = render("add-source", tv = true, windows = true, data = RenderData(linkPreview = Load.Ready(preview))) {
        AddSourceLinkDialog(preview.url, isTv = true, onDone = {})
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
