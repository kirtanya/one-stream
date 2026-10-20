package com.kirtanya.reel.core.launcher

/**
 * Who may read the launcher provider. One Stream and One Launcher are signed with different keys,
 * so a signature permission can't work: the provider checks the calling package by name AND by
 * One Launcher's signing certificate (a look-alike package name alone is not enough).
 */
object LauncherCaller {
    val PACKAGES = setOf("com.kirtanya.one_launcher", "com.kirtanya.one_launcher.tv")

    /** SHA-256 of One Launcher's signing certificate (phone and TV; public, not a secret). */
    const val CERT_SHA256 = "423fb6e70fe5a2f2a39f0b98eb8207b92e36b48700409b19a4e73208674f8c11"

    val certSha256: ByteArray get() = hex(CERT_SHA256)

    /**
     * True when [callingUid] is this app ([ownUid]) or one of [PACKAGES] signed with [CERT_SHA256].
     * [packagesForUid] / [hasCert] wrap PackageManager; any failure counts as "not allowed".
     */
    fun isAllowed(
        callingUid: Int,
        ownUid: Int,
        packagesForUid: (Int) -> List<String>?,
        hasCert: (pkg: String, sha256: ByteArray) -> Boolean,
    ): Boolean {
        if (callingUid == ownUid) return true
        val packages = runCatching { packagesForUid(callingUid) }.getOrNull().orEmpty()
        val digest = certSha256
        return packages.any { it in PACKAGES && runCatching { hasCert(it, digest) }.getOrDefault(false) }
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
