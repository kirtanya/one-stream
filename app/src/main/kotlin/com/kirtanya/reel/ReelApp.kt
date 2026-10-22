package com.kirtanya.reel

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.kirtanya.reel.core.sources.OkHttpFetcher
import com.kirtanya.reel.core.sources.PluginRuntime
import com.kirtanya.reel.core.sources.SourceAggregator
import com.kirtanya.reel.data.AppFetcher
import com.kirtanya.reel.data.PlaybackSettings
import com.kirtanya.reel.data.ProviderApps
import com.kirtanya.reel.data.WatchHistoryStore
import com.kirtanya.reel.data.SourcesStore
import com.kirtanya.reel.data.TmdbRepository
import com.kirtanya.reel.engine.TorrentEngine
import com.kirtanya.reel.server.LocalStreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the app-wide singletons. No DI framework: the graph is small. */
class ReelApp : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http by lazy { TmdbRepository.createHttp() }
    private val engineLazy = lazy { TorrentEngine(this).also { engineCreated.value = true } }
    /** True once something created the engine (the home disk's P2P gate waits for it, never creates it). */
    val engineCreated = kotlinx.coroutines.flow.MutableStateFlow(false)
    val engine: TorrentEngine by engineLazy
    /** The engine if something already created it (problem reports read it but must not start it). */
    val engineIfCreated: TorrentEngine? get() = if (engineLazy.isInitialized()) engine else null
    val streamServer: LocalStreamServer by lazy { LocalStreamServer(engine) }
    val tmdb by lazy { TmdbRepository(http) }
    val providerApps by lazy { ProviderApps(this) }
    val fetcher by lazy { AppFetcher(this, OkHttpFetcher(http, BuildConfig.TMDB_TOKEN)) }
    val pluginRuntime by lazy { PluginRuntime(fetcher) }
    val sources by lazy { SourcesStore(this, fetcher, pluginRuntime) }
    val aggregator by lazy { SourceAggregator() }
    val addonStore by lazy { com.kirtanya.reel.data.AddonStore(this, fetcher) }
    val history by lazy { WatchHistoryStore(this) }
    val watchlist by lazy { com.kirtanya.reel.data.WatchlistStore(this) }
    val sourceMarks by lazy { com.kirtanya.reel.data.SourceMarksStore(this) }
    val watched by lazy { com.kirtanya.reel.data.WatchedStore(this, history) }
    val hidden by lazy { com.kirtanya.reel.data.HiddenStore(this) }
    val sourcePrefs by lazy { com.kirtanya.reel.data.SourcePrefsStore(this) }
    val titlePrefs by lazy { com.kirtanya.reel.data.TitlePrefsStore(this) }
    val autoDownloader by lazy { com.kirtanya.reel.data.AutoDownloader(this) }
    /** The phone as the TV's remote (wake + "Send to TV"). */
    val tvRemote by lazy { com.kirtanya.reel.tvremote.TvRemote(this) }
    val playbackSettings by lazy { PlaybackSettings(this) }
    /** Which title / episode each download is for (Downloads → Change source). */
    val downloadOrigins by lazy { com.kirtanya.reel.data.DownloadOriginStore(this) }
    /** Problem reports: crashes, freezes, stalls, errors and "Report a problem" (docs/MACHINE_FIRST_PLAN.md step 1). */
    val reports by lazy { com.kirtanya.reel.report.ReportCenter(this) }

    /** TVs receive sources from the phone; phones send them. */
    val isTv by lazy {
        getSystemService(android.app.UiModeManager::class.java).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    /** Home disk: settings, Keystore secrets, connection manager, phone requests / TV agent (docs/HOME_DISK.md). */
    val home by lazy { com.kirtanya.reel.homedisk.Home(this) }
    val tvSync by lazy { com.kirtanya.reel.data.sync.TvSync(this, sources, watchlist, watched, hidden) }
    val phoneSync by lazy {
        val syncHttp = http.newBuilder().readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
        com.kirtanya.reel.data.sync.PhoneSync(this, sources, watchlist, watched, hidden, OkHttpFetcher(syncHttp, null), appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is reported too.
        reports.install()
        appScope.launch { sources.ensureReady() }
        // Finishing something in the player ticks it as watched (and so it syncs to the TV).
        appScope.launch { history.entries.collect { watched.recordHistory(it) } }
        autoDownloader.start()
        // Before anything can start the engine: the P2P network rule must hold downloads from the first moment.
        home.start()
        if (isTv) tvSync.start() else phoneSync.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()

    companion object {
        fun get(context: Context): ReelApp = context.applicationContext as ReelApp
    }
}
