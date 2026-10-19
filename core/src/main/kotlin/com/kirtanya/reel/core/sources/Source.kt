package com.kirtanya.reel.core.sources

import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.magnet.buildMagnet
import com.kirtanya.reel.core.tmdb.MediaType

enum class SourceKind { TORRENT, DIRECT, EXTERNAL }

/** What we're looking for: one film, or one episode of a show. */
data class SourceRequest(
    val tmdbId: Int,
    val imdbId: String?,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** Cache / dedupe key, e.g. "tv:1399:s1e3". */
    val key: String get() = "${type.path}:$tmdbId" + if (season != null) ":s${season}e$episode" else ""
}

data class SubtitleTrack(val url: String, val language: String, val name: String? = null)

/** One playable option from an addon or plugin, normalised. */
data class Source(
    val kind: SourceKind,
    /** Release name / description, as the provider wrote it. */
    val title: String,
    /** Addon or plugin that found it. */
    val provider: String,
    val quality: Quality,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Full magnet link when the provider gave one (keeps web seeds), else built from [infoHash]. */
    val magnet: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val filename: String? = null,
    val trackers: List<String> = emptyList(),
    val externalUrl: String? = null,
    /** Deep links into the service's own app (from addons like WatchHub). */
    val appUrl: String? = null,
    val tvAppUrl: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val bingeGroup: String? = null,
) {
    /** Two sources with the same key are the same file. */
    val key: String
        get() = when (kind) {
            SourceKind.TORRENT -> "bt:$infoHash:${fileIdx ?: filename ?: "-"}"
            SourceKind.DIRECT -> "url:$url"
            SourceKind.EXTERNAL -> "ext:$externalUrl"
        }

    /** Magnet to hand the engine (torrents only). */
    fun magnetUri(): String? = when {
        kind != SourceKind.TORRENT -> null
        magnet != null -> magnet
        infoHash != null -> buildMagnet(infoHash, filename ?: title.lineSequence().firstOrNull(), trackers)
        else -> null
    }

    companion object {
        /** Builds a torrent source from a magnet link, or null if it isn't one. */
        fun fromMagnet(magnet: String, title: String, provider: String, quality: Quality, fileIdx: Int? = null, filename: String? = null, seeders: Int? = null, sizeBytes: Long? = null): Source? {
            val m = Magnet.parse(magnet) ?: return null
            return Source(
                kind = SourceKind.TORRENT, title = title, provider = provider, quality = quality, magnet = magnet,
                infoHash = m.infoHash, fileIdx = fileIdx, filename = filename, trackers = m.trackers,
                seeders = seeders, sizeBytes = sizeBytes ?: m.sizeBytes,
            )
        }
    }
}
