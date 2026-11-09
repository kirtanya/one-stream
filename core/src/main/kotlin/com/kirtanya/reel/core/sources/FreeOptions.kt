package com.kirtanya.reel.core.sources

import com.kirtanya.reel.core.tmdb.Provider
import com.kirtanya.reel.core.tmdb.WatchProviders

/**
 * A legal free way to watch the title: a service TMDB lists as free (or free with ads) in India. It is external: the
 * service's own app opens, never the in-app player.
 */
data class FreeOption(val provider: Provider, val withAds: Boolean) {
    val title: String get() = "Free on ${provider.name}"
    val detail: String get() = if (withAds) "Opens in its app · with ads" else "Opens in its app"
}

/** "Free on <Service>" rows for the Sources page and the TV source panel, from TMDB's "where to watch". */
object FreeOptions {
    /** A page can't give a pile of services priority over the sources themselves. */
    const val MAX = 3

    /**
     * TMDB's `free` list first, then `ads`, once per service. Subscription tiers that only carry ads ("Netflix Standard
     * with Ads") and channel add-ons ("Amazon Channel") sit in those lists too but cost money: "Free on Netflix" would
     * be a lie, so anything named like that is left out; the genuinely free services are named plainly (JioHotstar,
     * MX Player, YouTube).
     */
    fun of(w: WatchProviders, max: Int = MAX): List<FreeOption> =
        (w.free.map { FreeOption(it, withAds = false) } + w.ads.map { FreeOption(it, withAds = true) })
            .filterNot { PAID_TIER.containsMatchIn(it.provider.name) }
            .distinctBy { it.provider.id }
            .distinctBy { it.provider.name.lowercase() }
            .take(max)

    private val PAID_TIER = Regex("(?i)with ads|channel")
}
