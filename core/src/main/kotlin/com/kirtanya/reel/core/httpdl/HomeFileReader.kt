package com.kirtanya.reel.core.httpdl

import com.kirtanya.reel.core.homedisk.HomeStore
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The in-app player's URI for a finished direct download: `reel-file://<id>/<file name>` (docs/HOME_DISK.md §25.7).
 * The id says which download; the name keeps the extension where ExoPlayer looks for it.
 */
object HomeFileRef {
    const val SCHEME = "reel-file"

    fun format(id: String, fileName: String): String = "$SCHEME://$id/" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")

    /** The download id of [uri], or null when it is not a `reel-file://` URI. */
    fun idOf(uri: String): String? {
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix)) return null
        val id = uri.removePrefix(prefix).substringBefore('/').lowercase()
        return id.takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun nameOf(uri: String): String? =
        uri.removePrefix("$SCHEME://").substringAfter('/', "").takeIf { it.isNotEmpty() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
}

/**
 * Sequential-friendly random-access reads of one committed file of a [HomeStore], for the player.
 *
 * A [UsbStore] read opens the file (and lists the folder to find its pieces) every time, which is far too slow for
 * the few-KB reads a player makes; so this reads [block]-sized chunks and serves the small reads from the last one.
 * A seek just reads the block it lands in. A read crossing a piece boundary is the store's business (it joins them), so
 * the player sees one stream.
 */
class HomeFileReader(private val store: HomeStore, private val path: String, private val block: Int = BLOCK) {
    /** Logical size; null when the file is not complete (a piece is missing, or it is not committed). */
    val size: Long? = store.size(path)

    private var cache = ByteArray(0)
    private var cacheStart = -1L
    private var cacheLen = 0

    /** Up to [len] bytes at [pos] into [buf]; -1 at or past the end. Fewer than [len] only at the end of the file. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val total = size ?: return -1
        if (len <= 0) return 0
        if (pos >= total) return -1
        var done = 0
        var at = pos
        while (done < len && at < total) {
            if (!cached(at)) fill(at, total) || return if (done > 0) done else -1
            val inBlock = (at - cacheStart).toInt()
            val n = minOf(len - done, cacheLen - inBlock)
            System.arraycopy(cache, inBlock, buf, off + done, n)
            done += n
            at += n
        }
        return done
    }

    private fun cached(at: Long) = cacheStart >= 0 && at >= cacheStart && at < cacheStart + cacheLen

    private fun fill(at: Long, total: Long): Boolean {
        if (cache.size != block) cache = ByteArray(block)
        val want = minOf(block.toLong(), total - at).toInt()
        var got = 0
        while (got < want) {
            val n = store.read(path, at + got, cache, got, want - got)
            if (n <= 0) break
            got += n
        }
        if (got == 0) {
            cacheStart = -1
            cacheLen = 0
            return false
        }
        cacheStart = at
        cacheLen = got
        return true
    }

    companion object {
        const val BLOCK = 1 shl 20
    }
}

/**
 * What the player says when a finished download won't play. Only a missing file means the disk; a read past the end
 * means the file is damaged (seen on the TV: its start came from an older, bigger copy, so the header points past the
 * end), and "check the disk" sent the user looking at a disk that was fine. Codes are media3's PlaybackException ones.
 */
object LocalPlayError {
    const val FILE_NOT_FOUND = 2005
    const val NO_PERMISSION = 2006
    const val POSITION_OUT_OF_RANGE = 2008

    const val DISK = "This download can't be read. If it is on a USB disk, check that the disk is plugged in."
    const val DAMAGED = "This download is damaged. Delete it and download it again."

    /** Only a read past the end is "damaged": any other read error (2000 included) can be a disk pulled out mid-film. */
    fun message(errorCode: Int): String = if (errorCode == POSITION_OUT_OF_RANGE) DAMAGED else DISK
}
