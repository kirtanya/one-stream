package com.kirtanya.reel.core.stream

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The in-app player's URI for a prepared stream: `reel-torrent://<key>/<fileIndex>/<token>/<name.ext>`.
 * The token identifies one prepared stream, so a superseded one fails fast; the file name keeps
 * the extension where ExoPlayer looks for it.
 */
data class TorrentStreamRef(val key: String, val fileIndex: Int, val token: Long, val name: String) {
    fun format(): String = "$SCHEME://$key/$fileIndex/$token/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    companion object {
        const val SCHEME = "reel-torrent"

        fun parse(uri: String): TorrentStreamRef? {
            val prefix = "$SCHEME://"
            if (!uri.startsWith(prefix)) return null
            val parts = uri.removePrefix(prefix).split('/', limit = 4)
            if (parts.size < 3) return null
            val key = parts[0].lowercase().takeIf { it.length == 40 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
            val idx = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val token = parts[2].toLongOrNull() ?: return null
            val name = parts.getOrNull(3)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: ""
            return TorrentStreamRef(key, idx, token, name)
        }
    }
}

/** Player buffer sizes from the media rate (docs/STREAMING_V2.md §6.2). */
object PlayerBuffers {
    /** How much already-played video to keep in memory, so short rewinds don't touch the disk. */
    fun backBufferMs(formFactor: FormFactor, bytesPerSec: Long): Int {
        val b = bytesPerSec.coerceAtLeast(1)
        return when (formFactor) {
            FormFactor.PHONE -> (32 * MiB * 1000 / b).coerceIn(5_000, 30_000)
            FormFactor.TV -> (12 * MiB * 1000 / b).coerceIn(3_000, 10_000)
        }.toInt()
    }

    /** Bytes per second: from size and duration when known, else from the quality label. */
    fun rate(sizeBytes: Long?, durationMs: Long?, qualityLabel: String?): Long {
        if (sizeBytes != null && sizeBytes > 0 && durationMs != null && durationMs > 0) return sizeBytes * 1000 / durationMs
        val mbps = when {
            qualityLabel?.contains("2160") == true || qualityLabel?.contains("4k", true) == true -> 25
            qualityLabel?.contains("1080") == true -> 8
            else -> 3
        }
        val byLabel = mbps * 1_000_000L / 8
        // No duration yet: be pessimistic, a big file is assumed to run no longer than 40 minutes.
        val bySize = if (sizeBytes != null && sizeBytes > 0) sizeBytes / (40 * 60) else 0L
        return maxOf(byLabel, bySize)
    }
}
