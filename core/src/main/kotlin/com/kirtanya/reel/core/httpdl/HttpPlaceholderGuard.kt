package com.kirtanya.reel.core.httpdl

import com.kirtanya.reel.core.player.ClipKind
import com.kirtanya.reel.core.player.PlaceholderCheck
import com.kirtanya.reel.core.player.StatusClip
import com.kirtanya.reel.core.stream.MiB

/** Is what a direct link answers the film, or the debrid service's "downloading" clip (docs/HOME_DISK.md §25.4)? */
sealed interface GuardVerdict {
    data object Ok : GuardVerdict

    /** The service has not cached it yet. [stat] is the reason for ReelStats ("pattern", "stub", "tiny", "size"). */
    data class NotReady(val stat: String) : GuardVerdict

    /**
     * The service's clip says it will never come: the account was turned down ([ClipKind.ACCOUNT], e.g. Torrentio's
     * "failed_access") or the file can't be fetched ([ClipKind.FAILED]). "Try again later" would be a lie for these.
     */
    data class Refused(val kind: ClipKind) : GuardVerdict

    /** Not a video at all (an error page). */
    data object NotVideo : GuardVerdict
}

object HttpPlaceholderGuard {
    /** AllDebrid's "not cached yet" clip is exactly this long. */
    const val KNOWN_STUB = 149_660L

    /** A film or episode under this size can't be one; the clips are a few hundred KB. */
    const val MIN_TITLE_BYTES = 20 * MiB

    /** Only a source that lists at least this much is compared with what arrives. */
    const val BIG_LISTED = 100 * MiB

    const val NOT_VIDEO_MESSAGE = "That link did not give a video. Pick another source"

    fun check(
        finalUrl: String,
        contentType: String?,
        totalBytes: Long?,
        listedBytes: Long?,
        isTitle: Boolean,
        firstBytes: ByteArray? = null,
    ): GuardVerdict {
        when (val kind = StatusClip.kind(finalUrl)) {
            ClipKind.ACCOUNT, ClipKind.FAILED -> return GuardVerdict.Refused(kind)
            else -> Unit
        }
        if (PlaceholderCheck.matchUrl(finalUrl) != null) return GuardVerdict.NotReady("pattern")
        val type = contentType?.lowercase()?.substringBefore(';')?.trim()
        if (type != null && (type.startsWith("text/") || type == "application/json" || type == "application/xml")) return GuardVerdict.NotVideo
        val b = firstBytes?.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
        if (b == '<' || b == '{') return GuardVerdict.NotVideo
        if (totalBytes != null) {
            if (totalBytes == KNOWN_STUB && (isTitle || (listedBytes ?: 0) > MIN_TITLE_BYTES)) return GuardVerdict.NotReady("stub")
            if (listedBytes != null && listedBytes >= BIG_LISTED && totalBytes < listedBytes / 10) return GuardVerdict.NotReady("size")
            if (isTitle && totalBytes < MIN_TITLE_BYTES) return GuardVerdict.NotReady("tiny")
        }
        return GuardVerdict.Ok
    }
}
