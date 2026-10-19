package com.kirtanya.reel

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.kirtanya.reel.data.ProviderApps
import com.kirtanya.reel.data.TmdbRepository
import com.kirtanya.reel.engine.TorrentEngine
import com.kirtanya.reel.server.LocalStreamServer

/** Holds the app-wide singletons. No DI framework: the graph is small. */
class ReelApp : Application(), SingletonImageLoader.Factory {
    val http by lazy { TmdbRepository.createHttp() }
    val engine: TorrentEngine by lazy { TorrentEngine(this) }
    val streamServer: LocalStreamServer by lazy { LocalStreamServer(engine) }
    val tmdb by lazy { TmdbRepository(http) }
    val providerApps by lazy { ProviderApps(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()

    companion object {
        fun get(context: Context): ReelApp = context.applicationContext as ReelApp
    }
}
