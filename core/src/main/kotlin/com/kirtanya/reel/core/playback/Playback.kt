package com.kirtanya.reel.core.playback

import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.SeasonSummary
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeNumber(val season: Int, val episode: Int) {
    val label: String get() = "S$season E$episode"
}

/**
 * Everything the player needs to play something and remember it afterwards: what it is
 * (for Continue watching and the next episode) and which source to play.
 */
@Serializable
data class Playback(
    /** History key: "movie:550", "tv:1399:s1e3", or "file:<infoHash>:<fileIdx>" for a raw torrent. */
    val key: String,
    /** Groups a show's episodes so Continue watching shows one card per show: "tv:1399". */
    val titleKey: String,
    val title: String,
    /** "S1 E3 · Lord Snow" for episodes. */
    val subtitle: String? = null,
    val tmdbType: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val year: Int? = null,
    val episode: EpisodeNumber? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val stillPath: String? = null,
    /** The episode after this one, if there is one. */
    val next: EpisodeNumber? = null,
    val source: Source,
) {
    /** "Game of Thrones · S1 E3" — what the player shows. */
    val displayTitle: String get() = listOfNotNull(title, episode?.label).joinToString(" · ")

    companion object {
        fun keyFor(tmdbType: String, tmdbId: Int, episode: EpisodeNumber?) =
            "$tmdbType:$tmdbId" + if (episode != null) ":s${episode.season}e${episode.episode}" else ""

        /** A torrent opened from Downloads / a magnet link: no TMDB info. */
        fun forTorrentFile(infoHash: String, fileIdx: Int?, name: String, magnet: String? = null, filename: String? = null): Playback {
            val source = Source(
                kind = SourceKind.TORRENT, title = name, provider = "Torrent",
                quality = com.kirtanya.reel.core.sources.QualityParser.parse(name),
                magnet = magnet, infoHash = infoHash, fileIdx = fileIdx, filename = filename,
            )
            val key = "file:$infoHash:${fileIdx ?: filename ?: "-"}"
            return Playback(key = key, titleKey = key, title = name, source = source)
        }
    }
}

/**
 * The episode after [current], moving to the next season when a season ends. Specials (season 0)
 * are skipped. TMDB season counts include announced episodes, so with [lastAired] (season, episode)
 * nothing after the latest aired episode is returned.
 */
fun nextEpisode(seasons: List<SeasonSummary>, current: EpisodeNumber, lastAired: Pair<Int, Int>? = null): EpisodeNumber? {
    val regular = seasons.filter { it.number > 0 && it.episodeCount > 0 }.sortedBy { it.number }
    val season = regular.firstOrNull { it.number == current.season }
    val next = if (season != null && current.episode < season.episodeCount) {
        EpisodeNumber(current.season, current.episode + 1)
    } else {
        regular.firstOrNull { it.number > current.season }?.let { EpisodeNumber(it.number, 1) }
    } ?: return null
    // A special (season 0) as the latest aired episode says nothing about regular seasons.
    if (lastAired != null && lastAired.first > 0 &&
        (next.season > lastAired.first || next.season == lastAired.first && next.episode > lastAired.second)
    ) return null
    return next
}

/** True when [episode] is at or before the latest aired episode (unknown → assume aired). */
fun hasAired(episode: EpisodeNumber, lastAired: Pair<Int, Int>?): Boolean =
    lastAired == null || lastAired.first == 0 ||
        episode.season < lastAired.first || episode.season == lastAired.first && episode.episode <= lastAired.second

/**
 * For auto-playing the next episode: stay with what worked — same binge group, then the same
 * provider at the same resolution, then the same provider, then the best overall.
 */
fun pickNextSource(previous: Source?, ranked: List<Source>): Source? {
    val playable = ranked.filter { it.kind != SourceKind.EXTERNAL }
    if (previous == null) return playable.firstOrNull()
    previous.bingeGroup?.let { group -> playable.firstOrNull { it.bingeGroup == group }?.let { return it } }
    playable.firstOrNull { it.provider == previous.provider && it.quality.resolution == previous.quality.resolution }?.let { return it }
    playable.firstOrNull { it.provider == previous.provider }?.let { return it }
    return playable.firstOrNull()
}
