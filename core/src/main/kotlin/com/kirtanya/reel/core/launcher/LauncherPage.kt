package com.kirtanya.reel.core.launcher

import com.kirtanya.reel.core.playback.ContinueItem
import com.kirtanya.reel.core.playback.EpisodeNumber
import com.kirtanya.reel.core.playback.WatchEntry
import com.kirtanya.reel.core.playback.hasAired
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.Episode
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleDetails
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tmdb.TmdbImages
import java.security.SecureRandom

/**
 * Part 2 of the launcher API: everything One Launcher needs to draw a title's whole page itself
 * (details, episodes, sources, downloads). Binding contract: docs/LAUNCHER_API.md.
 */
object LauncherPage {
    /** Sources wait for every addon / plugin like the Sources screen, but the binder thread gives up here. */
    const val SOURCES_TIMEOUT_MS = 25_000L

    /** A prepared ("prepare") stream that nobody plays stops after this long without reads. */
    const val PREPARE_IDLE_MS = 5 * 60_000L

    val DETAILS_COLUMNS = LauncherApi.COLUMNS + listOf(
        "overview", "genres", "runtime_min", "seasons", "status", "in_watchlist", "resume_key", "resume_progress",
    )
    val EPISODE_COLUMNS = listOf("season", "episode", "name", "overview", "still", "air_date", "aired", "progress", "link")
    val SOURCE_COLUMNS = listOf(
        "token", "name", "provider", "quality", "size_bytes", "seeds", "kind", "languages", "best", "play_link", "downloadable",
        // Added later, so at the end (never reorder): what a cast link's hint names.
        "hash", "file", "url",
    )
    val DOWNLOAD_COLUMNS = listOf("key", "title", "subtitle", "progress", "state", "size_bytes", "play_link")
    val RESULT_KEYS = listOf("ok", "message")

    /** Action names for ContentProvider.call (docs/LAUNCHER_API.md). */
    const val WATCHLIST_ADD = "watchlist_add"
    const val WATCHLIST_REMOVE = "watchlist_remove"
    const val DOWNLOAD = "download"
    const val PREPARE = "prepare"
    const val CANCEL_DOWNLOAD = "cancel_download"
    /** One Launcher's TV "Speed up": stop the engine if nothing is in use (StopEngine). No extras. */
    const val STOP_ENGINE = "stop_engine"
    /** One Launcher's TV title page opened: start the engine now so Play doesn't wait for it. No extras. */
    const val WARM_ENGINE = "warm_engine"

    /** A title from call() extras: `type` + `tmdb` (int, long or text — launchers differ). */
    fun titleRef(type: Any?, tmdb: Any?): TitleRef? {
        val t = LauncherApi.mediaType(type as? String) ?: return null
        val id = when (tmdb) {
            is Int -> tmdb
            is Long -> tmdb.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            is String -> tmdb.trim().toIntOrNull()
            else -> null
        }?.takeIf { it > 0 } ?: return null
        return TitleRef(t, id)
    }

    /** What a play link does when its token is gone (process restarted): the title page, if the link names one. */
    fun playFallback(link: LauncherLink.Play, tokenKnown: Boolean): TitleRef? = if (tokenKnown) null else link.fallback

    // ------------------------------------------------------------------ details

    /** [continueItems] in the app's order; the title's card there gives resume_key / resume_progress. */
    fun detailsRow(d: TitleDetails, inWatchlist: Boolean, continueItems: List<ContinueItem>): Array<Any?> {
        val ref = d.summary.ref
        val item = LauncherApi.findContinue(continueItems, ref.toString())
            ?.takeIf { it.entry.playback.titleKey == ref.toString() }
        val resume = item as? ContinueItem.Resume
        val seasons = if (ref.type == MediaType.TV) d.seasons.count { it.number > 0 } else null
        return (LauncherApi.titleRow(d.summary, d.imdbId).values().toList() + listOf<Any?>(
            d.summary.overview.takeIf { it.isNotBlank() },
            d.genres.joinToString(", ").takeIf { it.isNotEmpty() },
            d.runtimeMin,
            seasons,
            d.status,
            if (inWatchlist) 1 else 0,
            item?.let(LauncherApi::continueKey),
            if (resume != null && resume.entry.durationMs > 0) resume.entry.progress.toDouble() else -1.0,
        )).toTypedArray()
    }

    // ------------------------------------------------------------------ episodes

    /**
     * Aired: by its air date when TMDB has one (ISO dates compare as text), else by the show's
     * latest aired episode. [today] is "yyyy-MM-dd".
     */
    fun aired(e: Episode, today: String, lastAired: Pair<Int, Int>?): Boolean {
        val date = e.airDate?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
        return if (date != null) date <= today else hasAired(EpisodeNumber(e.season, e.number), lastAired)
    }

    /**
     * [history] gives each episode's progress; [bestToken] the best source the launcher already
     * found for that episode (via `/sources`), else the link opens the show's page.
     */
    fun episodeRows(
        tvId: Int,
        episodes: List<Episode>,
        today: String,
        lastAired: Pair<Int, Int>?,
        history: (key: String) -> WatchEntry?,
        bestToken: (season: Int, episode: Int) -> String?,
    ): List<Array<Any?>> = episodes.sortedBy { it.number }.map { e ->
        val ref = TitleRef(MediaType.TV, tvId)
        val entry = history("tv:$tvId:s${e.season}e${e.number}")
        val token = bestToken(e.season, e.number)
        arrayOf(
            e.season, e.number, e.name, e.overview.takeIf { it.isNotBlank() }, TmdbImages.still(e.stillPath, "w300"),
            e.airDate?.takeIf { it.isNotBlank() }, if (aired(e, today, lastAired)) 1 else 0,
            entry?.let { if (it.durationMs > 0) (if (it.finished) 1.0 else it.progress.toDouble()) else null } ?: -1.0,
            token?.let { LauncherLink.play(it, ref, e.season, e.number) } ?: LauncherLink.title(ref),
        )
    }

    // ------------------------------------------------------------------ sources

    fun kind(s: Source) = when (s.kind) {
        SourceKind.TORRENT -> "torrent"
        SourceKind.DIRECT -> "direct"
        SourceKind.EXTERNAL -> "service"
    }

    /** What the app's own Download button handles: torrents (engine) and direct links (DownloadManager). */
    fun downloadable(s: Source) = s.kind == SourceKind.TORRENT || (s.kind == SourceKind.DIRECT && s.url != null)

    /** Best match exactly like the Sources screen: the first non-service source of the ranked list that isn't a trailer or extra. */
    fun best(ranked: List<Source>): Source? = ranked.firstOrNull { it.kind != SourceKind.EXTERNAL && !it.isExtra }

    /** The first non-blank line of an addon's description (the release name). */
    fun name(s: Source) = s.title.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: s.title.trim()

    /** [tokens] parallel to [ranked]. */
    fun sourceRows(ranked: List<Source>, tokens: List<String>, ref: TitleRef, season: Int?, episode: Int?): List<Array<Any?>> {
        val bestKey = best(ranked)?.key
        return ranked.mapIndexed { i, s ->
            arrayOf(
                tokens[i], name(s), s.provider,
                if (s.kind == SourceKind.EXTERNAL) null else s.quality.label,
                s.sizeBytes ?: -1L, s.seeders ?: -1, kind(s), languages(s.title),
                if (s.key == bestKey) 1 else 0,
                LauncherLink.play(tokens[i], ref, season, episode),
                if (downloadable(s)) 1 else 0,
                CastChoice.hashOf(s),
                if (s.kind == SourceKind.TORRENT) s.fileIdx ?: -1 else -1,
                if (s.kind == SourceKind.DIRECT) s.url else null,
            )
        }
    }

    /**
     * Audio languages named in a release / description ("Hindi", "ENG", 🇮🇳, "Multi", "Dual audio"),
     * comma-separated in first-seen order; null when it names none. Same reading as the Sources page.
     */
    fun languages(text: String): String? =
        com.kirtanya.reel.core.sources.SourceFacts.languages(text).joinToString(", ").takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ downloads

    /** The engine's phase name (TorrentPhase) → the contract's state. */
    fun downloadState(phase: String, userPaused: Boolean, error: Boolean): String = when {
        error || phase == "ERROR" -> "failed"
        phase == "FINISHED" || phase == "SEEDING" -> "done"
        phase == "PAUSED" -> if (userPaused) "paused" else "queued"
        else -> "downloading"
    }

    fun downloadRow(infoHash: String, title: String, subtitle: String?, progress: Float, state: String, sizeBytes: Long): Array<Any?> =
        arrayOf(
            infoHash, title, subtitle,
            if (state == "done") 1.0 else progress.toDouble().coerceIn(0.0, 1.0),
            state, sizeBytes.takeIf { it > 0 } ?: -1L, LauncherLink.playDownload(infoHash),
        )
}

/**
 * Opaque tokens for sources the provider handed out, kept in memory (least recently used dropped
 * past [capacity]). A token is valid for this app process only; after a restart the play link
 * falls back to the title page.
 */
class SourceTokens<T>(private val capacity: Int = 500, private val newToken: () -> String = ::randomToken) {
    private val byToken = object : LinkedHashMap<String, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > capacity
    }
    /** Request key ("tv:1399:s1e3") → the best source's token, for episode play links. */
    private val bestFor = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    @Synchronized fun put(value: T): String {
        var token = newToken()
        while (byToken.containsKey(token)) token = newToken()
        byToken[token] = value
        return token
    }

    @Synchronized fun get(token: String): T? = byToken[token]

    @Synchronized fun setBest(requestKey: String, token: String?) {
        if (token == null) bestFor.remove(requestKey) else bestFor[requestKey] = token
    }

    /** The best token for [requestKey], if it is still held. */
    @Synchronized fun best(requestKey: String): String? = bestFor[requestKey]?.takeIf { byToken.containsKey(it) }

    companion object {
        private val random = SecureRandom()

        /** 24 hex characters; unguessable, so only a caller of the provider can play one. */
        fun randomToken(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        fun looksLikeToken(s: String) = Regex("[0-9a-zA-Z_-]{8,64}").matches(s)
    }
}
