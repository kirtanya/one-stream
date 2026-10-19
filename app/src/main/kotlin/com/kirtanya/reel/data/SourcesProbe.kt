package com.kirtanya.reel.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.ui.sources.rankPrefs
import com.kirtanya.reel.ui.sources.requestFor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Developer check without touching the screen. Only adb can send it (guarded by the DUMP permission):
 * `adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe --ei tmdb 10378 [--es type tv --ei season 1 --ei episode 1]`
 * `[--es addAddon <manifest url>] [--es removeAddon <manifest url>]` installs / removes an addon around the search.
 * Results go to logcat under "ReelProbe".
 */
class SourcesProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = ReelApp.get(context)
        val pending = goAsync()
        val type = if (intent.getStringExtra("type") == "tv") MediaType.TV else MediaType.MOVIE
        val ref = TitleRef(type, intent.getIntExtra("tmdb", 10378))
        val season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }
        app.appScope.launch {
            try {
                withTimeout(60_000) {
                    app.sources.ensureReady()
                    intent.getStringExtra("addAddon")?.let { Log.i(TAG, "added addon ${app.sources.addAddon(it).manifest.name}") }
                    val providers = app.sources.providers { name, line -> Log.i(TAG, "  [$name] $line") }
                    Log.i(TAG, "providers: ${providers.joinToString { it.name }}")
                    val d = app.tmdb.details(ref)
                    val req = requestFor(d, season, episode)
                    Log.i(TAG, "searching ${req.key} \"${req.title}\" (${req.year}) imdb=${req.imdbId}")
                    app.aggregator.search(req, providers, rankPrefs(context, isTv = false)).collect { s ->
                        Log.i(TAG, "update: ${s.sources.size} found, pending=${s.pending}, failed=${s.failed}")
                        if (s.done) s.sources.forEachIndexed { i, src ->
                            Log.i(TAG, "#$i ${src.kind} ${src.quality.label} [${src.provider}] ${src.title.lineSequence().first()} " +
                                "size=${src.sizeBytes} seeds=${src.seeders} ${src.url ?: src.infoHash}")
                        }
                    }
                }
            } catch (t: Throwable) {
                // Log.e hides traces for network errors, so spell the cause chain out.
                Log.e(TAG, "probe failed: " + generateSequence(t) { it.cause }.joinToString(" ← ") { it.toString() })
            } finally {
                intent.getStringExtra("removeAddon")?.let { app.sources.removeAddon(it); Log.i(TAG, "removed addon $it") }
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReelProbe"
    }
}
