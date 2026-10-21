package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.playback.EpisodeNumber
import kotlinx.serialization.Serializable

/** Settings → Downloads: automatic downloads and cleanup (per device). */
@Serializable
data class AutoDownloadSettings(
    /** Download the next episodes of My-list shows you're watching (home Wi-Fi only on the phone). */
    val enabled: Boolean = false,
    /** How many unwatched episodes to keep ready per show. */
    val keepNext: Int = 2,
    /** Delete downloads you've watched after this many days; null = keep them. */
    val deleteWatchedAfterDays: Int? = null,
    /** Stream cache limit in GB; null = the engine's own default. */
    val cacheLimitGb: Int? = null,
) {
    companion object {
        val KEEP_CHOICES = listOf(1, 2, 3, 5)
        val DELETE_CHOICES = listOf<Int?>(null, 1, 7, 30)
        val CACHE_CHOICES = listOf<Int?>(null, 2, 5, 10, 20)
    }
}

/** A finished or running download the app knows the film / episode of. */
data class KnownDownload(val infoHash: String, val origin: DownloadOrigin, val finished: Boolean)

object AutoDownload {
    /**
     * The episodes to have downloaded for one show: the first [keep] unwatched aired episodes after
     * the last watched one. A show you haven't started yet gets nothing (it's only on your list).
     */
    fun wanted(tvId: Int, aired: List<EpisodeNumber>, keep: Int, isWatched: (String) -> Boolean): List<EpisodeNumber> {
        val last = aired.indexOfLast { isWatched(Watched.episodeKey(tvId, it)) }
        if (last < 0) return emptyList()
        return aired.drop(last + 1).filterNot { isWatched(Watched.episodeKey(tvId, it)) }.take(keep)
    }

    /** Of [wanted], those with no download yet (running or finished). */
    fun missing(tvId: Int, wanted: List<EpisodeNumber>, downloads: List<KnownDownload>): List<EpisodeNumber> =
        wanted.filter { ep -> downloads.none { it.origin.type == "tv" && it.origin.tmdbId == tvId && it.origin.season == ep.season && it.origin.episode == ep.episode } }

    /**
     * Finished downloads to delete: watched at least [days] days ago ([watchedAt] gives when, null =
     * not watched). Running downloads and unknown files are never touched.
     */
    fun toDelete(downloads: List<KnownDownload>, days: Int?, now: Long, watchedAt: (String) -> Long?): List<String> {
        if (days == null) return emptyList()
        val cutoff = now - days * 24L * 60 * 60 * 1000
        return downloads.filter { d ->
            d.finished && watchedAt(keyOf(d.origin))?.let { it <= cutoff } == true
        }.map { it.infoHash }
    }

    fun keyOf(o: DownloadOrigin) = o.type + ":" + o.tmdbId + if (o.season != null && o.episode != null) ":s${o.season}e${o.episode}" else ""
}
