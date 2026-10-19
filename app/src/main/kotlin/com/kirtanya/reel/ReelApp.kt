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
import com.kirtanya.reel.data.ProviderApps
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
    val engine: TorrentEngine by lazy { TorrentEngine(this) }
    val streamServer: LocalStreamServer by lazy { LocalStreamServer(engine) }
    val tmdb by lazy { TmdbRepository(http) }
    val providerApps by lazy { ProviderApps(this) }
    val fetcher by lazy { AppFetcher(this, OkHttpFetcher(http, BuildConfig.TMDB_TOKEN)) }
    val pluginRuntime by lazy { PluginRuntime(fetcher) }
    val sources by lazy { SourcesStore(this, fetcher, pluginRuntime) }
    val aggregator by lazy { SourceAggregator() }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { sources.ensureReady() }
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
