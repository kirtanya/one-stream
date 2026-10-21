package com.kirtanya.reel.core.store

/**
 * Addons One Stream installs by itself: only the official Stremio OpenSubtitles v3 addon (legal,
 * subtitles only, listed in Stremio's official collection). It is an ordinary addon once installed:
 * listed in Settings → Addons, can be switched off or removed, and a removed one is never re-added.
 */
object BuiltInAddons {
    const val OPENSUBTITLES_URL = "https://opensubtitles-v3.strem.io/manifest.json"

    /**
     * Its manifest as served (checked 2026-10-21), so the first run installs it without a network round
     * trip; the addon is asked over the network only when a film plays.
     */
    const val OPENSUBTITLES_MANIFEST =
        """{"id":"org.stremio.opensubtitlesv3","version":"1.0.0","name":"OpenSubtitles v3","description":"OpenSubtitles v3 Addon for Stremio","catalogs":[],"resources":["subtitles"],"types":["movie","series"],"idPrefixes":["tt"],"logo":"http://www.strem.io/images/addons/opensubtitles-logo.png"}"""

    /** The one-time step's flag: set once it ran, whatever it found. */
    const val OPENSUBTITLES_STEP = "addOpenSubtitles"

    data class Default(val url: String, val manifestJson: String)

    /** What one start should do: addons to add, steps to mark done. */
    data class Migration(val add: List<Default>, val markDone: Set<String>)

    /**
     * New installs and existing ones get OpenSubtitles once ([done] holds the steps already run). If it's
     * already there nothing is added; once the step ran, a later removal is the user's choice and stays.
     */
    fun migrate(installedUrls: Collection<String>, done: Set<String>): Migration {
        if (OPENSUBTITLES_STEP in done) return Migration(emptyList(), emptySet())
        val have = installedUrls.any { sameAddon(it, OPENSUBTITLES_URL) }
        return Migration(
            add = if (have) emptyList() else listOf(Default(OPENSUBTITLES_URL, OPENSUBTITLES_MANIFEST)),
            markDone = setOf(OPENSUBTITLES_STEP),
        )
    }

    /** Same addon whatever the spelling (stremio://, trailing slash, query). */
    fun sameAddon(a: String, b: String): Boolean =
        com.kirtanya.reel.core.sources.StremioAddon.baseUrl(a).lowercase() == com.kirtanya.reel.core.sources.StremioAddon.baseUrl(b).lowercase()
}
