package com.kirtanya.reel.data

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.launcher.CastChoice
import com.kirtanya.reel.core.launcher.CastChoice.Decision
import com.kirtanya.reel.core.launcher.CastRequest
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.sources.SourceSearch
import com.kirtanya.reel.ui.sources.download
import com.kirtanya.reel.ui.sources.rankPrefs
import com.kirtanya.reel.ui.sources.requestFor
import com.kirtanya.reel.ui.sources.wantedLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Download on TV" from the phone (onestream://cast/download), run inside EngineService so nothing
 * on the TV screen changes: the app's own source search, the hinted source (or the ranker's best
 * downloadable one), then the Sources page's own Download (with its DownloadOrigin, so Downloads →
 * Change source works). Only sources the search returned are ever downloaded.
 */
object CastDownloads {
    private const val TAG = "ReelCast"

    suspend fun run(context: Context, request: CastRequest) {
        val app = ReelApp.get(context)
        val d = runCatching { app.tmdb.details(request.ref) }.getOrElse {
            Log.w(TAG, "cast download: TMDB failed: ${it.message}")
            return toast(context, CastChoice.downloadFailed("this title"))
        }
        val label = CastChoice.label(d.summary.title, request.season, request.episode)
        var last: SourceSearch? = null
        val decided = runCatching {
            app.sources.ensureReady()
            withTimeoutOrNull(LauncherPage.SOURCES_TIMEOUT_MS) {
                app.aggregator.search(requestFor(d, request.season, request.episode), app.sources.providers(), rankPrefs(app, app.isTv, wantedLanguages(app, d)))
                    .onEach { last = it }
                    .map { CastChoice.decide(it.sources, it.done, request) { true } }
                    .first { it != Decision.Wait }
            }
        }.onFailure { Log.w(TAG, "cast download: search failed: ${it.message}") }.getOrNull()
            // Timed out: choose from what arrived.
            ?: CastChoice.decide(last?.sources.orEmpty(), done = true, request) { true }
        val source = (decided as? Decision.Take)?.source ?: return toast(context, CastChoice.downloadNothing(label))
        Log.i(TAG, "cast download: ${request.ref} → ${source.provider} ${source.key}")
        val origin = DownloadOrigin(d.summary.ref.type.path, d.summary.ref.id, request.season, request.episode, label)
        // The app's own Download button (direct links go to Android's download manager, which wants the main thread).
        withContext(Dispatchers.Main) { download(app, source, label, origin, startedMessage = CastChoice.downloadStarted(label)) }
    }

    private suspend fun toast(context: Context, message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
