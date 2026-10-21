package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.sources.SourceRequest
import com.kirtanya.reel.core.tmdb.MediaType
import kotlinx.serialization.Serializable

/**
 * The user's own marks on a source, from its long-press menu. A **favourite** is the one source
 * to use for a film / episode (it becomes the Recommended pick); **starred** sources are ones worth
 * remembering (they go to the top of the list). Several can be starred, one is the favourite.
 */
@Serializable
data class SourceMark(
    /** [SourceRequest.key]: "movie:550" or "tv:1399:s1e3". */
    val requestKey: String,
    /** "movie:550" / "tv:1399": a show's marks follow the same release (binge group) to other episodes. */
    val titleKey: String,
    val sourceKey: String,
    val bingeGroup: String? = null,
    val favourite: Boolean = false,
    val starred: Boolean = false,
    val changedAt: Long = 0,
)

/** A download the engine has for a torrent, for the "Downloaded" / "Downloading 42%" tag. */
data class DownloadTag(val progress: Float, val finished: Boolean)

/** What the app last played for this film / episode, from the watch history. */
data class LastPlayed(
    /** The exact film / episode asked for. */
    val same: Source? = null,
    /** A show: the source of the latest episode played (any episode). */
    val latestOfShow: Source? = null,
)

object SourceMarks {
    fun titleKey(req: SourceRequest) = "${req.type.path}:${req.tmdbId}"

    /** The mark that applies to [s]: this exact source here, or (shows) the same release marked on another episode. */
    fun markFor(marks: List<SourceMark>, req: SourceRequest, s: Source): SourceMark? =
        marks.firstOrNull { it.requestKey == req.key && it.sourceKey == s.key }
            ?: if (req.type == MediaType.TV && s.bingeGroup != null) {
                marks.filter { it.titleKey == titleKey(req) && it.bingeGroup == s.bingeGroup }.maxByOrNull { it.changedAt }
            } else null

    /** The favourite among [ranked] (never a trailer or a service), if it came back. */
    fun favourite(ranked: List<Source>, marks: List<SourceMark>, req: SourceRequest): Source? =
        ranked.firstOrNull { !it.isExtra && it.kind != SourceKind.EXTERNAL && markFor(marks, req, it)?.favourite == true }

    /** Starred first, otherwise the ranker's order. */
    fun order(ranked: List<Source>, marks: List<SourceMark>, req: SourceRequest): List<Source> =
        ranked.sortedBy { if (markFor(marks, req, it)?.starred == true) 0 else 1 }

    /**
     * Sets [favourite] and/or [starred] for [s] (null = leave as is). Making one source the favourite
     * clears the favourite of every other source of the same film / episode (and, for a show, of the
     * other releases of that show). A mark with nothing set is dropped.
     */
    fun set(marks: List<SourceMark>, req: SourceRequest, s: Source, favourite: Boolean? = null, starred: Boolean? = null, now: Long): List<SourceMark> {
        val title = titleKey(req)
        val old = marks.firstOrNull { it.requestKey == req.key && it.sourceKey == s.key }
            ?: markFor(marks, req, s)?.let { SourceMark(req.key, title, s.key, s.bingeGroup, it.favourite, it.starred) }
            ?: SourceMark(req.key, title, s.key, s.bingeGroup)
        val next = old.copy(
            favourite = favourite ?: old.favourite,
            starred = starred ?: old.starred,
            changedAt = now,
        )
        val others = marks
            .filterNot { it.requestKey == req.key && it.sourceKey == s.key }
            .map { m ->
                val sameThing = m.requestKey == req.key || (req.type == MediaType.TV && m.titleKey == title)
                if (favourite == true && sameThing && m.favourite) m.copy(favourite = false, changedAt = now) else m
            }
        return (others + next).filter { it.favourite || it.starred }.takeLast(MAX_MARKS)
    }

    /** The user's own tags for a row, before the release tags: what they did with it. */
    fun tags(s: Source, req: SourceRequest, marks: List<SourceMark>, last: LastPlayed, download: DownloadTag?): List<String> {
        val mark = markFor(marks, req, s)
        return listOfNotNull(
            when {
                last.same?.key == s.key -> "Last played"
                last.same == null && s.bingeGroup != null && last.latestOfShow?.bingeGroup == s.bingeGroup -> "Same as last episode"
                else -> null
            },
            "Favourite".takeIf { mark?.favourite == true },
            "Starred".takeIf { mark?.starred == true },
            download?.let { if (it.finished) "Downloaded" else "Downloading ${(it.progress * 100).toInt()}%" },
        )
    }

    /** What "Share link" sends: the magnet, the direct link, or the service page. */
    fun shareText(s: Source): String? = when (s.kind) {
        SourceKind.TORRENT -> s.magnetUri()
        SourceKind.DIRECT -> s.url
        SourceKind.EXTERNAL -> s.externalUrl
    }

    private const val MAX_MARKS = 500
}
