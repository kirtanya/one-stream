package com.kirtanya.reel.core.homedisk

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stream URLs the TV hands out for its home library (docs/HOME_DISK.md §11.2):
 * `v1.<deviceId>.<key>.<fileIdx>.<expirySec>.<mac>`, mac = the first 16 bytes of
 * HMAC-SHA256(streamKey, everything before it), base64url. Used from Phase 7; the format is fixed now.
 */
object StreamToken {
    const val VALID_SEC = 6 * 3600L
    const val REFRESH_BEFORE_SEC = 30 * 60L

    data class Parsed(val deviceId: String, val key: String, val fileIdx: Int, val expirySec: Long)

    fun make(deviceId: String, key: String, fileIdx: Int, expirySec: Long, streamKey: ByteArray): String {
        require(deviceId.matches(SAFE) && key.matches(SAFE)) { "ids must be plain" }
        val body = "v1.$deviceId.$key.$fileIdx.$expirySec"
        return "$body.${mac(streamKey, body)}"
    }

    /** Null unless the mac checks, it hasn't expired and [paired] still knows the device. */
    fun verify(token: String, streamKey: ByteArray, nowSec: Long, paired: (String) -> Boolean): Parsed? {
        val parts = token.split('.')
        if (parts.size != 6 || parts[0] != "v1") return null
        val body = parts.take(5).joinToString(".")
        if (!MessageDigest.isEqual(mac(streamKey, body).toByteArray(), parts[5].toByteArray())) return null
        val p = Parsed(parts[1], parts[2], parts[3].toIntOrNull() ?: return null, parts[4].toLongOrNull() ?: return null)
        if (nowSec >= p.expirySec || !paired(p.deviceId)) return null
        return p
    }

    fun needsRefresh(p: Parsed, nowSec: Long) = p.expirySec - nowSec < REFRESH_BEFORE_SEC

    private val SAFE = Regex("[A-Za-z0-9_-]+")

    private fun mac(key: ByteArray, body: String): String {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(body.toByteArray()).copyOf(16))
    }
}
