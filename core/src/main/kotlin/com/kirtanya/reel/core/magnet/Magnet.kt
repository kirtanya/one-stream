package com.kirtanya.reel.core.magnet

import java.net.URLDecoder
import java.net.URLEncoder

/** A parsed `magnet:` link. [infoHash] is always 40-char lowercase hex (v1). */
data class Magnet(
    val infoHash: String,
    val name: String? = null,
    val trackers: List<String> = emptyList(),
    val sizeBytes: Long? = null,
) {
    fun toUri(): String = buildMagnet(infoHash, name, trackers)

    companion object {
        /** Returns null when the text isn't a usable v1 magnet link. */
        fun parse(text: String): Magnet? {
            val uri = text.trim()
            if (!uri.startsWith("magnet:?", ignoreCase = true)) return null
            val params = uri.substringAfter('?').split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isEmpty()) null else key.lowercase() to decode(part.substringAfter('=', ""))
                }
            val hash = params.filter { it.first == "xt" }
                .map { it.second }
                .firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
                ?.substring("urn:btih:".length)
                ?.let(::normalizeInfoHash)
                ?: return null
            return Magnet(
                infoHash = hash,
                name = params.firstOrNull { it.first == "dn" }?.second?.takeIf { it.isNotBlank() },
                trackers = params.filter { it.first == "tr" }.map { it.second }.filter { it.isNotBlank() }.distinct(),
                sizeBytes = params.firstOrNull { it.first == "xl" }?.second?.toLongOrNull(),
            )
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
    }
}

/** Builds a magnet link; trackers are de-duplicated and kept in order. */
fun buildMagnet(infoHash: String, name: String? = null, trackers: List<String> = emptyList()): String {
    val hash = normalizeInfoHash(infoHash) ?: error("Invalid info-hash: $infoHash")
    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)
        if (!name.isNullOrBlank()) append("&dn=").append(encode(name))
        trackers.filter { it.isNotBlank() }.distinct().forEach { append("&tr=").append(encode(it)) }
    }
}

/**
 * Accepts a v1 info-hash as 40 hex chars or 32 base32 chars and returns lowercase hex,
 * or null if it is neither.
 */
fun normalizeInfoHash(raw: String): String? {
    val value = raw.trim()
    return when {
        value.length == 40 && value.all { it.isHex() } -> value.lowercase()
        value.length == 32 -> base32ToHex(value.uppercase())
        else -> null
    }
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun base32ToHex(value: String): String? {
    var buffer = 0L
    var bits = 0
    val out = StringBuilder(40)
    for (c in value) {
        val v = BASE32.indexOf(c)
        if (v < 0) return null
        buffer = (buffer shl 5) or v.toLong()
        bits += 5
        while (bits >= 4) {
            bits -= 4
            out.append("0123456789abcdef"[((buffer shr bits) and 0xF).toInt()])
        }
    }
    return out.toString().takeIf { it.length == 40 }
}

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
