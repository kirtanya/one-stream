package com.kirtanya.reel.core.launcher

import com.kirtanya.reel.core.magnet.normalizeInfoHash
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceBrowser
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef

/**
 * Which source the phone had in mind (from its `/sources` row: `hash`, `file`, `url`). Only a
 * hint: it picks among the sources the TV's own search returns and never plays anything by itself,
 * because any app (or a remote) can send a cast link.
 */
data class CastHint(val hash: String? = null, val file: Int? = null, val url: String? = null) {
    val isEmpty get() = hash == null && url == null
}

/**
 * `onestream://cast/play|download?type=…&tmdb=…[&season&episode][&hash&file&url]`: "Play on TV" /
 * "Download on TV" from One Launcher on the phone, sent as an Android TV remote app link
 * (docs/LAUNCHER_API.md, Part 3).
 */
data class CastRequest(
    val download: Boolean,
    val ref: TitleRef,
    val season: Int?,
    val episode: Int?,
    val hint: CastHint,
) {
    companion object {
        /** Null unless it names a film, or a show with season + episode (a whole show can't start). */
        fun parse(action: String?, params: Map<String, String>): CastRequest? {
            val download = when (action?.lowercase()) {
                "play" -> false
                "download" -> true
                else -> return null
            }
            val type = LauncherApi.mediaType(params["type"]) ?: return null
            val id = params["tmdb"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val season = params["season"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            val episode = params["episode"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            if (type == MediaType.TV && (season == null || episode == null)) return null
            val hint = CastHint(
                hash = params["hash"]?.trim()?.let(::normalizeInfoHash),
                file = params["file"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
                url = params["url"]?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) },
            )
            return if (type == MediaType.TV) CastRequest(download, TitleRef(type, id), season, episode, hint)
            else CastRequest(download, TitleRef(type, id), null, null, hint)
        }

        fun link(download: Boolean, ref: TitleRef, season: Int? = null, episode: Int? = null, hint: CastHint = CastHint()): String =
            "${LauncherLink.SCHEME}://cast/${if (download) "download" else "play"}?type=${ref.type.path}&tmdb=${ref.id}" +
                (if (season != null && episode != null) "&season=$season&episode=$episode" else "") +
                (hint.hash?.let { "&hash=$it" } ?: "") + (hint.file?.let { "&file=$it" } ?: "") +
                (hint.url?.let { "&url=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
    }
}

/**
 * Picks the source a cast link plays / downloads. Runs on every search update: [decide] says wait,
 * take this one, or nothing usable (only once the search is [done] or timed out).
 */
object CastChoice {
    sealed interface Decision {
        data object Wait : Decision
        data class Take(val source: Source) : Decision
        data object Nothing : Decision
    }

    /** A torrent's info-hash as the launcher's `hash` column shows it (lowercase hex). */
    fun hashOf(s: Source): String? = if (s.kind != SourceKind.TORRENT) null
        else s.infoHash?.let(::normalizeInfoHash) ?: s.magnet?.let { com.kirtanya.reel.core.magnet.Magnet.parse(it)?.infoHash }

    /**
     * The search result the hint names. Same torrent + same file wins; a torrent whose file the hint
     * or the source leaves open matches too (but a season pack's *other* file never does). A direct
     * link matches by its exact URL.
     */
    fun matchHint(ranked: List<Source>, hint: CastHint): Source? {
        if (hint.isEmpty) return null
        hint.hash?.let { hash ->
            val same = ranked.filter { hashOf(it) == hash }
            same.firstOrNull { hint.file != null && it.fileIdx == hint.file }?.let { return it }
            same.firstOrNull { hint.file == null || it.fileIdx == null }?.let { return it }
        }
        hint.url?.let { url -> ranked.firstOrNull { it.kind == SourceKind.DIRECT && it.url == url }?.let { return it } }
        return null
    }

    /**
     * Play: the hinted source if this device can stream it ([fits]), else the Sources page's
     * Recommended pick (one that fits; if none fits, the ranker's winner, like the page).
     */
    fun forPlay(ranked: List<Source>, hint: CastHint, fits: (Source) -> Boolean): Source? {
        val hinted = matchHint(ranked, hint)?.takeIf { it.kind != SourceKind.EXTERNAL && fits(it) }
        return hinted ?: SourceBrowser.recommended(ranked, fits)
    }

    /** Download: the hinted source if it can be downloaded, else the ranker's best downloadable one (size doesn't matter). */
    fun forDownload(ranked: List<Source>, hint: CastHint): Source? {
        val hinted = matchHint(ranked, hint)?.takeIf(LauncherPage::downloadable)
        return hinted ?: ranked.firstOrNull(LauncherPage::downloadable)
    }

    /**
     * While the search runs: take the hinted source as soon as it arrives (no need to wait for slow
     * addons); otherwise wait until [done] and choose from everything that came back.
     */
    fun decide(ranked: List<Source>, done: Boolean, request: CastRequest, fits: (Source) -> Boolean): Decision {
        val hinted = matchHint(ranked, request.hint)
        val early = if (request.download) hinted?.takeIf(LauncherPage::downloadable)
            else hinted?.takeIf { it.kind != SourceKind.EXTERNAL && fits(it) }
        if (early != null) return Decision.Take(early)
        if (!done) return Decision.Wait
        val pick = if (request.download) forDownload(ranked, request.hint) else forPlay(ranked, request.hint, fits)
        return pick?.let { Decision.Take(it) } ?: Decision.Nothing
    }

    /** "Fight Club", "Game of Thrones · S1 E3" (the Sources page's download label): toast, screen, download origin. */
    fun label(title: String, season: Int?, episode: Int?) =
        title + if (season != null && episode != null) " · " + com.kirtanya.reel.core.playback.EpisodeNumber(season, episode).label else ""

    fun downloadStarted(label: String) = "Downloading $label on this TV"
    fun downloadNothing(label: String) = "Couldn't find a source to download $label"
    fun downloadFailed(label: String) = "Couldn't download $label: no answer from TMDB"
}
