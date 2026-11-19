package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.homedisk.DownloadPlacement
import com.kirtanya.reel.core.launcher.LauncherPage
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import kotlinx.serialization.Serializable

/** Settings → Storage → "When I add to My list": what adding a title does about downloading it. */
@Serializable
enum class OnAddToList(val label: String, val note: String) {
    /** Opens the Sources page in download mode; closing it keeps the title on the list. */
    ASK("Ask which source", "After adding, you pick the source to download. Closing the page just keeps the title on the list."),

    /** Starts the ranker's top pick without asking. */
    BEST("Download the best source", "Starts the top-ranked source right away."),

    /** Only saves the title. */
    JUST_ADD("Just add", "Only saves the title; nothing is downloaded.");

    companion object {
        val DEFAULT = ASK
    }
}

/** What "download when added" picks for one title (docs/DECISIONS.md 2026-11-19). */
object MyListDownload {
    /**
     * The one episode of a series to download when it is added: the first unwatched aired episode after the last one
     * watched, or the first aired episode of a show not started yet (S1 E1). Never the whole show. Null when nothing has
     * aired yet or you are caught up, so there is nothing to fetch.
     */
    fun episodeFor(tvId: Int, aired: List<EpisodeNumber>, isWatched: (String) -> Boolean): EpisodeNumber? {
        if (aired.isEmpty()) return null
        val last = aired.indexOfLast { isWatched(Watched.episodeKey(tvId, it)) }
        if (last < 0) return aired.first()
        return aired.drop(last + 1).firstOrNull { !isWatched(Watched.episodeKey(tvId, it)) }
    }

    /**
     * "Download the best source": the ranker's top pick that the app can download itself. [ranked] is already in
     * SourceRanker order (the aggregator sorts it), so this only skips what can't be downloaded: service links
     * and trailers / extras.
     */
    fun bestToDownload(ranked: List<Source>, underFatLimit: Boolean = false): Source? {
        val best = ranked.firstOrNull { it.kind != SourceKind.EXTERNAL && !it.isExtra && LauncherPage.downloadable(it) } ?: return null
        // TV with the USB disk (FAT32, docs/HOME_DISK.md §24.3): prefer a source under 4 GiB at the same or one-lower quality.
        return if (underFatLimit) DownloadPlacement.preferUnderLimit(ranked, best) else best
    }

    /** True when [origin] already has a download (running or finished), so adding doesn't start or ask for a second one. */
    fun alreadyDownloading(origin: DownloadOrigin, downloads: List<KnownDownload>): Boolean =
        downloads.any { it.origin.type == origin.type && it.origin.tmdbId == origin.tmdbId && it.origin.season == origin.season && it.origin.episode == origin.episode }
}

/**
 * The film / episodes that are completely downloaded (a finished or seeding download whose origin is known), for the
 * "Downloaded" badge on cards and title pages. A download still running or paused counts for nothing here.
 */
data class DownloadedSet(private val keys: Set<String>) {
    private val shows: Set<Int> = keys.mapNotNull { k -> k.takeIf { it.startsWith("tv:") }?.split(":")?.getOrNull(1)?.toIntOrNull() }.toSet()

    fun movie(tmdbId: Int) = "movie:$tmdbId" in keys

    fun episode(tvId: Int, ep: EpisodeNumber) = "tv:$tvId:s${ep.season}e${ep.episode}" in keys

    /** At least one episode of this show is downloaded: the cheap test before anything is fetched for the show. */
    fun anyEpisode(tvId: Int) = tvId in shows

    /** Every aired episode downloaded: the show's own badge. A show with nothing aired is never "complete". */
    fun show(tvId: Int, aired: List<EpisodeNumber>) = aired.isNotEmpty() && aired.all { episode(tvId, it) }

    val isEmpty get() = keys.isEmpty()

    companion object {
        val EMPTY = DownloadedSet(emptySet())

        fun of(downloads: List<KnownDownload>) = DownloadedSet(downloads.filter { it.finished }.map { AutoDownload.keyOf(it.origin) }.toSet())
    }
}
