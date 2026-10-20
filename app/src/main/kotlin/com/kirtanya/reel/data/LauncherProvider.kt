package com.kirtanya.reel.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log
import com.kirtanya.reel.BuildConfig
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.launcher.LauncherApi
import com.kirtanya.reel.core.launcher.LauncherApi.Query
import com.kirtanya.reel.core.launcher.LauncherCaller
import com.kirtanya.reel.core.tmdb.TitleRef
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only API for One Launcher (docs/LAUNCHER_API.md). Exported, but every call is checked:
 * only One Launcher (package name + its signing certificate, see [LauncherCaller]) may read.
 * The logic lives in core `LauncherApi`; this class only fetches and fills cursors.
 */
class LauncherProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        val query = LauncherApi.parse(uri.path) { runCatching { uri.getQueryParameter(it) }.getOrNull() }
        // Binder threads only: clear the caller identity so our own file / network access is ours.
        val token = Binder.clearCallingIdentity()
        try {
            return answer(query)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun answer(query: Query): Cursor {
        val app = ReelApp.get(context!!)
        return when (query) {
            Query.Info -> MatrixCursor(LauncherApi.INFO_COLUMNS.toTypedArray(), 1).apply {
                addRow(arrayOf<Any?>(LauncherApi.API_VERSION, BuildConfig.VERSION_NAME))
            }
            is Query.Search -> rows(
                if (query.q.isEmpty()) emptyList()
                else network { LauncherApi.searchRows(app.tmdb.search(query.q), query.limit) }.orEmpty(),
            )
            is Query.Continue -> rows(LauncherApi.continueRows(app.history.continueWatching(), query.limit))
            is Query.Watchlist -> rows(LauncherApi.watchlistRows(app.watchlist.all.value, query.limit))
            is Query.Title -> rows(
                network {
                    val tmdbId = query.tmdb
                    if (tmdbId != null) {
                        val d = app.tmdb.details(TitleRef(query.type, tmdbId))
                        LauncherApi.titleRow(d.summary, d.imdbId ?: query.imdb)
                    } else {
                        app.tmdb.findByImdb(query.imdb!!, query.type)?.let { LauncherApi.titleRow(it, query.imdb) }
                    }
                }?.let(::listOf).orEmpty(),
            )
            Query.Unknown -> rows(emptyList())
        }
    }

    private fun rows(list: List<LauncherApi.Row>) = MatrixCursor(LauncherApi.COLUMNS.toTypedArray(), list.size).apply {
        list.forEach { addRow(it.values()) }
    }

    /**
     * Runs a TMDB call with a hard deadline. The call runs on the app scope (OkHttp's blocking read
     * can't be interrupted by a timeout around it); the binder thread waits at most
     * [LauncherApi.NETWORK_TIMEOUT_MS] and gives up — null, so the launcher gets an empty cursor.
     */
    private fun <T> network(block: suspend () -> T): T? {
        val app = ReelApp.get(context!!)
        val job = app.appScope.async { runCatching { block() }.onFailure { Log.w(TAG, "launcher query failed: ${it.message}") }.getOrNull() }
        return runBlocking { withTimeoutOrNull(LauncherApi.NETWORK_TIMEOUT_MS) { job.await() } }
            .also { if (job.isActive) job.cancel() }
    }

    private fun checkCaller() {
        val pm = context!!.packageManager
        val allowed = LauncherCaller.isAllowed(
            callingUid = Binder.getCallingUid(),
            ownUid = Process.myUid(),
            packagesForUid = { pm.getPackagesForUid(it)?.toList() },
            hasCert = { pkg, sha -> pm.hasSigningCertificate(pkg, sha, PackageManager.CERT_INPUT_SHA256) },
        )
        if (!allowed) throw SecurityException("Only One Launcher may read One Stream's launcher API")
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    private companion object {
        const val TAG = "ReelLauncher"
    }
}
