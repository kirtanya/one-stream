package com.kirtanya.reel.core.stream

/**
 * The one key every engine map uses for a torrent: its v1 info-hash in lowercase hex when it
 * has one (magnets, most torrents, hybrid v1+v2), else its v2 hash truncated to 40 hex chars.
 *
 * libtorrent's `info_hash()` returns the *best* hash, which for a hybrid torrent changes from
 * v1 to v2 the moment metadata arrives; keying by it made metadata waits time out.
 */
object TorrentKey {
    private val ZERO = "0".repeat(40)

    fun canonical(v1Hex: String?, bestHex: String): String =
        (v1Hex?.lowercase()?.takeIf { it.isNotEmpty() && it != ZERO } ?: bestHex).lowercase()
}
