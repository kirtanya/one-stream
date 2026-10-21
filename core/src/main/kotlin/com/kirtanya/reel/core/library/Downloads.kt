package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.stream.TorrentKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Which film or episode a download was started for, so the Downloads screen can offer
 * "Change source" (the Sources page for the same title / episode). Torrents added from a magnet
 * link have none.
 */
@Serializable
data class DownloadOrigin(
    /** "movie" or "tv" (MediaType.path). */
    val type: String,
    val tmdbId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    /** "The Night Manager · S2 E6", for the confirmation dialog. */
    val label: String,
)

/** files/download_origins.json: info-hash → origin. */
object DownloadOrigins {
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = MapSerializer(String.serializer(), DownloadOrigin.serializer())

    fun parse(text: String?): Map<String, DownloadOrigin> =
        text?.let { runCatching { json.decodeFromString(codec, it) }.getOrNull() }.orEmpty()

    fun encode(map: Map<String, DownloadOrigin>): String = json.encodeToString(codec, map)
}

/** What choosing a new source for an existing download means. */
enum class SwapKind {
    /** The very file already downloading: nothing to do. */
    SAME_FILE,

    /** Another file of the same torrent (a season pack): it's added; nothing fetched is lost. */
    SAME_TORRENT,

    /** A different torrent: the old one and its data are deleted once the new one starts. */
    NEW_TORRENT,

    /** A direct link: Android's download manager fetches it; the torrent and its data are deleted. */
    DIRECT,

    /** A service link can't be downloaded. */
    NOT_DOWNLOADABLE,
}

/** The confirmation the user sees before a download's source is replaced. */
data class SwapConfirm(val title: String, val body: String, val confirm: String)

object SourceSwap {
    fun kind(currentKey: String, currentFile: Int?, next: Source): SwapKind = when (next.kind) {
        SourceKind.EXTERNAL -> SwapKind.NOT_DOWNLOADABLE
        SourceKind.DIRECT -> SwapKind.DIRECT
        SourceKind.TORRENT -> {
            val hash = next.infoHash?.lowercase()
            when {
                hash == null || TorrentKey.canonical(hash, hash) != currentKey.lowercase() -> SwapKind.NEW_TORRENT
                next.fileIdx != null && currentFile != null && next.fileIdx != currentFile -> SwapKind.SAME_TORRENT
                else -> SwapKind.SAME_FILE
            }
        }
    }

    /**
     * What the dialog says. [doneBytes] is what this download has fetched so far, already
     * formatted ("4.2 GB"); null or "0 B" means nothing to lose.
     */
    fun confirm(kind: SwapKind, label: String, finished: Boolean, done: String?): SwapConfirm? {
        val lost = when {
            finished -> "The finished download will be deleted from this device."
            done == null || done.startsWith("0 ") -> "Nothing has been downloaded yet, so nothing is lost."
            else -> "The $done downloaded so far will be lost."
        }
        return when (kind) {
            SwapKind.SAME_FILE, SwapKind.NOT_DOWNLOADABLE -> null
            SwapKind.SAME_TORRENT -> SwapConfirm(
                "Switch to this file?",
                "It's in the same torrent, so what's downloaded so far is kept and this file is added to “$label”.",
                "Switch",
            )
            SwapKind.NEW_TORRENT -> SwapConfirm("Replace this download?", "“$label” will download from the new source instead. $lost", "Replace")
            SwapKind.DIRECT -> SwapConfirm("Replace this download?", "“$label” will download from the direct link instead. $lost", "Replace")
        }
    }
}
