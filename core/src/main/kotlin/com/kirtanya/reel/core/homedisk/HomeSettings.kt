package com.kirtanya.reel.core.homedisk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** "When I tap Download" (docs/HOME_DISK.md §17.3). */
@Serializable
enum class DownloadWhere(val label: String) { ASK("Ask me"), AT_HOME("At home"), ON_PHONE("On this phone") }

/** A Wi-Fi the user answered for (by fingerprint). [label] is what they'll recognise: when and how it was seen. */
@Serializable
data class KnownNetwork(val fingerprint: String, val label: String, val allowed: Boolean, val since: Long = 0)

/**
 * Stream cache and retention on the disk (docs/HOME_DISK.md §13; product decisions 2026-10-21): what the TV
 * plays stays cached [days] days (0 = until space is needed), the cache may use [cacheSharePct] % of the disk,
 * auto-cache when opening details is off by default.
 */
@Serializable
data class RetentionSettings(
    val keepTvViewing: Boolean = true,
    val days: Int = 7,
    val cacheSharePct: Int = 40,
    val autoCacheDetails: Boolean = false,
) {
    companion object {
        val DAY_CHOICES = listOf(1, 3, 7, 14, 30, 0)
        val SHARE_CHOICES = listOf(20, 30, 40, 50, 60)
        fun daysLabel(d: Int) = if (d == 0) "Until space is needed" else if (d == 1) "1 day" else "$d days"
    }
}

/**
 * Home storage settings: plain values only. The password lives in the Keystore (HomeSecrets), never here.
 * The same model goes to the TV inside `home.config` (plus the password, encrypted end to end).
 */
@Serializable
data class HomeSettings(
    val address: String = DEFAULT_ADDRESS,
    val share: String = "",
    val user: String = "",
    /** Only when the Test found an SMB1-only server (§4.5). */
    val smb1: Boolean = false,
    /** Fingerprint of the home network, saved by the first successful Test (§5.2). */
    val homeFingerprint: String? = null,
    val homeSavedAt: Long = 0,
    val downloadWhere: DownloadWhere = DownloadWhere.ASK,
    val copyPhoneDownloads: Boolean = true,
    /** "Download on this phone over mobile data": off, so downloads wait for Wi-Fi. */
    val mobileData: Boolean = false,
    val networks: List<KnownNetwork> = emptyList(),
    val retention: RetentionSettings = RetentionSettings(),
) {
    /** Address, folder and user filled in (the password is checked separately). */
    val filledIn: Boolean get() = address.isNotBlank() && share.isNotBlank() && user.isNotBlank()

    val known: KnownNetworks
        get() = KnownNetworks(
            allowed = networks.filter { it.allowed }.mapTo(HashSet()) { it.fingerprint },
            work = networks.filter { !it.allowed }.mapTo(HashSet()) { it.fingerprint },
        )

    /** Remembers the answer for one Wi-Fi; a later answer for the same network replaces the earlier one. */
    fun answer(fingerprint: String, label: String, allowed: Boolean, now: Long): HomeSettings =
        copy(networks = networks.filterNot { it.fingerprint == fingerprint } + KnownNetwork(fingerprint, label, allowed, now))

    fun forget(fingerprint: String): HomeSettings = copy(networks = networks.filterNot { it.fingerprint == fingerprint })

    /** `smb://192.168.1.1/<share>/` with no credentials in it (they never go in a URL, §4.3). */
    val shareUrl: String
        get() = "smb://${address.trim()}/${share.trim().trim('/')}/"

    companion object {
        const val DEFAULT_ADDRESS = "192.168.1.1"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(s: HomeSettings): String = json.encodeToString(serializer(), s)
        fun decode(text: String?): HomeSettings = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: HomeSettings()
    }
}
