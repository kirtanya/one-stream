package com.kirtanya.reel.data

import android.content.Context
import com.kirtanya.reel.core.sources.FetchRequest
import com.kirtanya.reel.core.sources.FetchResponse
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.OkHttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Network fetcher for addons/plugins, plus `asset://` for the plugins bundled in the app. */
class AppFetcher(private val context: Context, private val network: OkHttpFetcher) : HttpFetcher {
    override suspend fun fetch(request: FetchRequest): FetchResponse {
        if (request.url.startsWith(ASSET)) {
            val path = request.url.removePrefix(ASSET)
            val bytes = withContext(Dispatchers.IO) { context.assets.open(path).use { it.readBytes() } }
            return FetchResponse(200, request.url, emptyMap(), bytes)
        }
        return network.fetch(request)
    }

    companion object {
        const val ASSET = "asset://"
    }
}
