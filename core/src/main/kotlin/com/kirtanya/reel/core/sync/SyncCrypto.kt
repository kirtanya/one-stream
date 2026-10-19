package com.kirtanya.reel.core.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secrets shared by a paired phone and TV. After pairing the secret never travels again:
 * the TV proves itself with an HMAC over the phone's challenge, and pushes are AES-GCM
 * encrypted (so addon URLs with keys in them aren't readable on the network).
 */
object SyncCrypto {
    private val random = SecureRandom()

    fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** HMAC-SHA256(secret, message), hex. */
    fun hmac(secretHex: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(secretHex), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison for proofs and tokens. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** AES-256-GCM with a key derived from the shared secret; [aad] binds it to one request (the TV's nonce). */
    fun encrypt(secretHex: String, plain: ByteArray, aad: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secretHex), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray())
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain))
    }

    /** Throws if the data was tampered with, the key is wrong, or [aad] differs. */
    fun decrypt(secretHex: String, data: String, aad: String): ByteArray {
        val raw = Base64.getDecoder().decode(data.trim())
        require(raw.size > 12 + 16) { "too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secretHex), GCMParameterSpec(128, raw, 0, 12))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(raw, 12, raw.size - 12)
    }

    private fun key(secretHex: String) =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(hexToBytes(secretHex) + "reel-sync-enc".toByteArray()), "AES")

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
