package com.kirtanya.reel.core.sync

import com.kirtanya.reel.core.library.WatchlistItem
import kotlinx.serialization.Serializable

/** Addons and plugin repositories as one device has them: what the phone sends the TV. */
@Serializable
data class SourcesBundle(
    val version: Int = 1,
    val addons: List<BundleAddon> = emptyList(),
    val repos: List<BundleRepo> = emptyList(),
    /** Who sent it, for "Last update from Pixel 8". */
    val from: String = "",
    /**
     * The sender's watchlist including removals; merged (newest change wins), not mirrored, so
     * titles saved on the TV survive. Null = the sender doesn't sync a watchlist (older app).
     */
    val watchlist: List<WatchlistItem>? = null,
)

@Serializable
data class BundleAddon(val url: String, val enabled: Boolean = true)

@Serializable
data class BundleRepo(
    val url: String,
    /** Plugin id → on/off, for plugins the sender has. */
    val plugins: Map<String, Boolean> = emptyMap(),
)

/** What to change so the receiver matches the sender. Built-in repos are never removed. */
data class SyncPlan(
    val addAddons: List<BundleAddon>,
    val removeAddons: List<String>,
    val toggleAddons: Map<String, Boolean>,
    val addRepos: List<BundleRepo>,
    val removeRepos: List<String>,
    /** repo URL → (plugin id → on/off) for repos both have. */
    val togglePlugins: Map<String, Map<String, Boolean>>,
) {
    val isEmpty: Boolean
        get() = addAddons.isEmpty() && removeAddons.isEmpty() && toggleAddons.isEmpty() &&
            addRepos.isEmpty() && removeRepos.isEmpty() && togglePlugins.isEmpty()

    /** "1 addon added, 2 plugin repos removed", read as "Synced just now · …". */
    fun describe(): String = listOfNotNull(
        count(addAddons.size, "addon", "added"),
        count(removeAddons.size, "addon", "removed"),
        count(addRepos.size, "plugin repo", "added"),
        count(removeRepos.size, "plugin repo", "removed"),
        (toggleAddons.size + togglePlugins.values.sumOf { it.size }).takeIf { it > 0 }?.let { "$it switched on/off" },
    ).joinToString(", ").ifEmpty { "already up to date" }

    private fun count(n: Int, noun: String, verb: String) = n.takeIf { it > 0 }?.let { "$it $noun${if (it > 1) "s" else ""} $verb" }

    companion object {
        /**
         * Makes [local] match [incoming] (the phone is the source of truth). Repos in [builtIn]
         * are kept; their plugin switches still follow the sender.
         */
        fun between(local: SourcesBundle, incoming: SourcesBundle, builtIn: Set<String> = emptySet()): SyncPlan {
            val localAddons = local.addons.associateBy { it.url }
            val incomingAddons = incoming.addons.associateBy { it.url }
            val localRepos = local.repos.associateBy { it.url }
            val incomingRepos = incoming.repos.associateBy { it.url }
            return SyncPlan(
                addAddons = incoming.addons.filter { it.url !in localAddons },
                removeAddons = local.addons.map { it.url }.filter { it !in incomingAddons },
                toggleAddons = incoming.addons.filter { a -> localAddons[a.url]?.let { it.enabled != a.enabled } == true }
                    .associate { it.url to it.enabled },
                addRepos = incoming.repos.filter { it.url !in localRepos && it.url !in builtIn },
                removeRepos = local.repos.map { it.url }.filter { it !in incomingRepos && it !in builtIn },
                togglePlugins = incoming.repos.mapNotNull { r ->
                    val mine = localRepos[r.url] ?: return@mapNotNull null
                    val changed = r.plugins.filter { (id, on) -> mine.plugins[id]?.let { it != on } == true }
                    if (changed.isEmpty()) null else r.url to changed
                }.toMap(),
            )
        }
    }
}
