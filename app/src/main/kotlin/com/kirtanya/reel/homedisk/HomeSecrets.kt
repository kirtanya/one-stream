package com.kirtanya.reel.homedisk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Small secrets (the home disk password, the phone ↔ TV pairing secrets) encrypted with an AES-256-GCM key
 * that lives in the Android Keystore (alias `onestream-home`, non-exportable, docs/HOME_DISK.md §6). The
 * ciphertext (iv ‖ data) sits in `noBackupFilesDir/home_secret.bin`; backups are off anyway.
 *
 * Values are never logged. Where the Keystore can't be used (screen renders on the Mac), [available] is
 * false and values only live in memory, so nothing secret is ever written in plain text.
 */
class HomeSecrets private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "home_secret.bin"))
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = MapSerializer(String.serializer(), String.serializer())
    private val lock = Any()
    private var values: MutableMap<String, String> = HashMap()

    /** False when the Keystore isn't there: callers keep their old storage instead of migrating to us. */
    val available: Boolean = runCatching { key() }.onFailure { Log.w(TAG, "keystore unavailable: ${it.javaClass.simpleName}") }.isSuccess

    init {
        if (available) values = runCatching { read() }.onFailure { Log.w(TAG, "secrets unreadable: ${it.javaClass.simpleName}") }.getOrNull() ?: HashMap()
    }

    fun get(name: String): String? = synchronized(lock) { values[name] }

    /** All values (for problem-report redaction only). */
    fun all(): Collection<String> = synchronized(lock) { values.values.toList() }

    /** Saves [changes] (null removes). Returns true when they are safely on disk (or nothing changed). */
    fun put(changes: Map<String, String?>): Boolean = synchronized(lock) {
        val next = HashMap(values)
        changes.forEach { (k, v) -> if (v.isNullOrEmpty()) next.remove(k) else next[k] = v }
        if (next == values) return true
        values = next
        if (!available) return false
        return runCatching { write(next) }.onFailure { Log.w(TAG, "secrets not saved: ${it.javaClass.simpleName}") }.isSuccess
    }

    fun put(name: String, value: String?): Boolean = put(mapOf(name to value))

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun read(): MutableMap<String, String> {
        if (!file.baseFile.exists()) return HashMap()
        val raw = file.readFully()
        if (raw.size <= 12) return HashMap()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        val plain = cipher.doFinal(raw, 12, raw.size - 12)
        return HashMap(json.decodeFromString(codec, plain.decodeToString()))
    }

    private fun write(map: Map<String, String>) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(json.encodeToString(codec, map).toByteArray())
        val out = file.startWrite()
        try {
            out.write(data)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        private const val TAG = "ReelHome"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "onestream-home"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        const val SMB_PASSWORD = "smb.password"
        fun pairTv(tvId: String) = "pair.tv.$tvId"
        fun pairPhone(phoneId: String) = "pair.phone.$phoneId"

        @Volatile private var instance: HomeSecrets? = null
        fun get(context: Context): HomeSecrets =
            instance ?: synchronized(this) { instance ?: HomeSecrets(context.applicationContext).also { instance = it } }
    }
}
