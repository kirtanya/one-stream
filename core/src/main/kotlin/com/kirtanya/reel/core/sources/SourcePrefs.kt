package com.kirtanya.reel.core.sources

/**
 * The user's source preferences on one device (Settings → Sources): a quality cap, never CAM,
 * providers to prefer or block. Not synced: the phone and the TV want different things.
 */
data class SourcePrefs(
    /** 2160, 1080, 720 or null for no cap. */
    val maxResolution: Int? = null,
    val neverCam: Boolean = false,
    val pinned: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
) {
    /** These preferences on top of [base] (the device's storage limit etc.). The preferred resolution never exceeds the cap. */
    fun applyTo(base: RankPrefs) = base.copy(
        maxResolution = maxResolution,
        preferredResolution = maxResolution?.let { minOf(it, base.preferredResolution) } ?: base.preferredResolution,
        neverCam = neverCam,
        pinned = pinned,
        blocked = blocked,
    )

    /** Pinning unblocks and blocking unpins: a provider is never both. */
    fun pin(label: String, on: Boolean) = copy(pinned = if (on) pinned + label else pinned - label, blocked = if (on) blocked - label else blocked)
    fun block(label: String, on: Boolean) = copy(blocked = if (on) blocked + label else blocked - label, pinned = if (on) pinned - label else pinned)

    companion object {
        val CAPS = listOf<Pair<Int?, String>>(null to "No limit", 2160 to "4K", 1080 to "1080p", 720 to "720p")
    }
}
