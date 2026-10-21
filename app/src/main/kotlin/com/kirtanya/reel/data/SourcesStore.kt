package com.kirtanya.reel.data

import android.content.Context
import android.util.Log
import com.kirtanya.reel.core.sources.AddonProvider
import com.kirtanya.reel.core.sources.FetchRequest
import com.kirtanya.reel.core.sources.HttpFetcher
import com.kirtanya.reel.core.sources.NuvioPlugins
import com.kirtanya.reel.core.sources.PluginInfo
import com.kirtanya.reel.core.sources.PluginProvider
import com.kirtanya.reel.core.sources.PluginRuntime
import com.kirtanya.reel.core.sources.SourceProvider
import com.kirtanya.reel.core.sources.StremioAddon
import com.kirtanya.reel.core.store.BuiltInAddons
import com.kirtanya.reel.core.sync.ApplyResult
import com.kirtanya.reel.core.sync.BundleAddon
import kotlinx.coroutines.CancellationException
import com.kirtanya.reel.core.sync.BundleRepo
import com.kirtanya.reel.core.sync.LinkKind
import com.kirtanya.reel.core.sync.SourceLinks
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.sync.SyncPlan
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledAddon(val url: String, val manifestJson: String, val enabled: Boolean = true) {
    val manifest get() = StremioAddon.parseManifest(manifestJson)
}

@Serializable
data class InstalledRepo(val url: String, val name: String, val version: String, val builtIn: Boolean = false, val updatedAt: Long = 0)

@Serializable
data class InstalledPlugin(
    val id: String,
    val repoUrl: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val filename: String,
    val types: List<String>,
    val enabled: Boolean = true,
    val logo: String? = null,
)

@Serializable
data class SourcesState(
    val addons: List<InstalledAddon> = emptyList(),
    val repos: List<InstalledRepo> = emptyList(),
    val plugins: List<InstalledPlugin> = emptyList(),
)

class SourceSetupException(message: String) : Exception(message)

/**
 * Installed Stremio addons and Nuvio-format plugin repositories. State lives in
 * files/sources.json; plugin code in files/plugins/<repo>/<id>.js. The built-in legal repo
 * (assets/plugins) is always present.
 */
class SourcesStore(private val context: Context, private val http: HttpFetcher, private val runtime: PluginRuntime) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(context.filesDir, "sources.json")
    private val codeDir = File(context.filesDir, "plugins")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    @Volatile private var builtInLoaded = false

    init {
        addBuiltInAddons()
    }

    /**
     * OpenSubtitles v3 once, on new and existing installs (core/store/BuiltInAddons): from its shipped
     * manifest, so it needs no network. The flag keeps a removal by the user final.
     */
    private fun addBuiltInAddons() {
        val steps = context.getSharedPreferences("sources_steps", Context.MODE_PRIVATE)
        val done = steps.getStringSet("done", emptySet()).orEmpty()
        val plan = BuiltInAddons.migrate(_state.value.addons.map { it.url }, done)
        if (plan.add.isNotEmpty()) {
            update { s -> s.copy(addons = s.addons + plan.add.map { InstalledAddon(it.url, it.manifestJson) }) }
            Log.i(TAG, "added built-in addons: ${plan.add.joinToString { it.url }}")
        }
        if (plan.markDone.isNotEmpty()) steps.edit().putStringSet("done", done + plan.markDone).apply()
    }

    /**
     * Reloads the built-in repo once per app start (it ships inside the APK, so it changes
     * with app updates) and refreshes other repos older than 6 h.
     */
    suspend fun ensureReady() {
        if (!builtInLoaded) {
            runCatching { addRepo(BUILTIN_REPO, builtIn = true) }
                .onSuccess { builtInLoaded = true }
                .onFailure { Log.w(TAG, "built-in repo failed", it) }
        }
        val stale = _state.value.repos.filter { !it.builtIn && System.currentTimeMillis() - it.updatedAt > REFRESH_MS }
        stale.forEach { repo -> runCatching { addRepo(repo.url, repo.builtIn, refresh = true) }.onFailure { Log.w(TAG, "refresh ${repo.url}", it) } }
    }

    /** Providers to ask for a title, in the user's order. */
    fun providers(log: (String, String) -> Unit = { name, line -> Log.d(TAG, "[$name] $line") }): List<SourceProvider> {
        val s = _state.value
        val addons = s.addons.filter { it.enabled }.mapNotNull { a ->
            runCatching { a.manifest }.getOrNull()?.takeIf { it.hasStreams }?.let { AddonProvider(a.url, it, http) }
        }
        val plugins = s.plugins.filter { it.enabled }.mapNotNull { p ->
            val code = codeFile(p.repoUrl, p.id).takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            val info = PluginInfo(p.id, p.name, p.version, p.description, p.filename, p.types, true, p.logo, null)
            PluginProvider(info, code, runtime, log = { log(p.name, it) })
        }
        return addons + plugins
    }

    /** Enabled addons that provide subtitles (e.g. OpenSubtitles v3). */
    fun subtitleAddons(): List<Pair<String, com.kirtanya.reel.core.sources.StremioManifest>> =
        _state.value.addons.filter { it.enabled }.mapNotNull { a ->
            runCatching { a.manifest }.getOrNull()?.takeIf { it.hasSubtitles }?.let { a.url to it }
        }

    // ---- links (Install buttons, stremio:// links, pasted URLs)

    /** What a link points at, for the "Add this?" dialog. [body] is what gets installed if the user says yes. */
    data class LinkPreview(val url: String, val kind: LinkKind, val name: String, val detail: String?, val body: String)

    suspend fun preview(link: String): LinkPreview {
        val url = SourceLinks.fromUserInput(link) ?: throw SourceSetupException("That isn't an addon or plugin link")
        val body = get(url)
        return when (SourceLinks.detectKind(body)) {
            LinkKind.ADDON -> StremioAddon.parseManifest(body).let { m ->
                if (!m.hasStreams && !m.hasSubtitles) throw SourceSetupException("${m.name} doesn't provide streams or subtitles, so One Stream can't use it")
                val what = listOfNotNull(if (m.hasStreams) "streams" else null, if (m.hasSubtitles) "subtitles" else null).joinToString(" + ")
                LinkPreview(url, LinkKind.ADDON, m.name, listOfNotNull("Stremio addon · $what", m.description).joinToString("\n"), body)
            }
            LinkKind.PLUGIN_REPO -> NuvioPlugins.parseManifest(body).let { r ->
                if (r.scrapers.isEmpty()) throw SourceSetupException("${r.name} has no plugins")
                LinkPreview(url, LinkKind.PLUGIN_REPO, r.name, listOfNotNull("Plugin repository · ${r.scrapers.size} plugins (scripts that run on this device)", r.description).joinToString("\n"), body)
            }
            LinkKind.UNKNOWN -> throw SourceSetupException("That link isn't a Stremio addon or a plugin repository")
        }
    }

    /** Installs exactly what [preview] showed (no second look at the link that could come back different). */
    suspend fun addPreviewed(preview: LinkPreview): String = when (preview.kind) {
        LinkKind.ADDON -> "Added ${addAddon(preview.url, prefetched = preview.body).manifest.name}"
        LinkKind.PLUGIN_REPO -> "Added ${addRepo(preview.url, prefetched = preview.body).name}"
        LinkKind.UNKNOWN -> throw SourceSetupException("That link isn't a Stremio addon or a plugin repository")
    }

    /** Adds whatever the link is (addon or plugin repo; a bare addon address works too). */
    suspend fun addFromLink(link: String): String = addPreviewed(preview(link))

    // ---- phone → TV sync

    /** This device's sources, as sent to a TV. */
    fun exportBundle(from: String = ""): SourcesBundle {
        val s = _state.value
        return SourcesBundle(
            addons = s.addons.map { BundleAddon(it.url, it.enabled) },
            repos = s.repos.map { r -> BundleRepo(r.url, s.plugins.filter { it.repoUrl == r.url }.associate { it.id to it.enabled }) },
            from = from,
        )
    }

    /**
     * Makes this device match [bundle] (sent by the phone). Links that can't be fetched are
     * skipped and reported; the built-in repo is never removed.
     */
    suspend fun applyBundle(bundle: SourcesBundle): ApplyResult {
        ensureReady()
        // One lock for the whole apply: no refresh or Settings action can interleave and undo part of it.
        return mutex.withLock {
            val builtIn = _state.value.repos.filter { it.builtIn }.map { it.url }.toSet()
            val plan = SyncPlan.between(exportBundle(), bundle, builtIn)
            val failed = mutableListOf<String>()
            plan.addAddons.forEach { a ->
                runCatching { addAddonLocked(a.url, null) }
                    .onSuccess { if (!a.enabled) setAddonEnabled(a.url, false) }
                    .onFailure { if (it is CancellationException) throw it; failed += a.url }
            }
            plan.addRepos.forEach { r ->
                runCatching { addRepoLocked(r.url, builtIn = false, prefetched = null, startedAt = 0) }
                    .onFailure { if (it is CancellationException) throw it; failed += r.url }
            }
            // Removals after the adds, so a failed add never leaves the TV with less than before.
            plan.removeAddons.forEach { removeAddon(it) }
            plan.removeRepos.forEach { removeRepo(it) }
            plan.toggleAddons.forEach { (url, on) -> setAddonEnabled(url, on) }
            // Plugin switches for every repo now present (new ones included), as the phone has them.
            bundle.repos.forEach { r -> r.plugins.forEach { (id, on) -> setPluginEnabled(r.url, id, on) } }
            ApplyResult(plan.describe() + if (failed.isEmpty()) "" else " (${failed.size} couldn't be reached)", failed.size)
        }
    }

    // ---- addons

    suspend fun addAddon(input: String, prefetched: String? = null): InstalledAddon = mutex.withLock { addAddonLocked(input, prefetched) }

    private suspend fun addAddonLocked(input: String, prefetched: String?): InstalledAddon {
        val url = StremioAddon.manifestUrl(input)
        val body = prefetched ?: get(url)
        val manifest = try {
            StremioAddon.parseManifest(body)
        } catch (e: Exception) {
            throw SourceSetupException("That link isn't a Stremio addon")
        }
        if (!manifest.hasStreams && !manifest.hasSubtitles) throw SourceSetupException("${manifest.name} doesn't provide streams or subtitles")
        val addon = InstalledAddon(url, body)
        update { s -> s.copy(addons = s.addons.filterNot { it.url == url } + addon) }
        return addon
    }

    fun setAddonEnabled(url: String, enabled: Boolean) =
        update { s -> s.copy(addons = s.addons.map { if (it.url == url) it.copy(enabled = enabled) else it }) }

    fun removeAddon(url: String) = update { s -> s.copy(addons = s.addons.filterNot { it.url == url }) }

    /** Repo URL → when it was removed: a refresh already in flight must not bring it back. */
    private val removedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---- plugin repos

    /** Adds or updates a plugin repo. [refresh] = background update: skipped if the repo was removed since. */
    suspend fun addRepo(input: String, builtIn: Boolean = false, prefetched: String? = null, refresh: Boolean = false): InstalledRepo {
        val startedAt = System.nanoTime()
        return mutex.withLock {
            val url = input.trim()
            if (refresh && _state.value.repos.none { it.url == url }) throw SourceSetupException("Removed")
            addRepoLocked(url, builtIn, prefetched, if (refresh) startedAt else 0)
        }
    }

    private suspend fun addRepoLocked(input: String, builtIn: Boolean, prefetched: String?, startedAt: Long): InstalledRepo {
        val url = input.trim()
        val manifest = try {
            NuvioPlugins.parseManifest(prefetched ?: get(url))
        } catch (e: SourceSetupException) {
            throw e
        } catch (e: Exception) {
            throw SourceSetupException("That link isn't a plugin repository")
        }
        if (manifest.scrapers.isEmpty()) throw SourceSetupException("${manifest.name} has no plugins")
        val previous = _state.value.plugins.filter { it.repoUrl == url }.associateBy { it.id }
        val installed = manifest.scrapers.mapNotNull { info ->
            val code = runCatching { get(NuvioPlugins.codeUrl(url, info.filename)) }.getOrElse {
                Log.w(TAG, "plugin ${info.id} download failed", it)
                return@mapNotNull null
            }
            withContext(Dispatchers.IO) { codeFile(url, info.id).apply { parentFile?.mkdirs() }.writeText(code) }
            InstalledPlugin(
                id = info.id, repoUrl = url, name = info.name, version = info.version, description = info.description,
                filename = info.filename, types = info.supportedTypes, logo = info.logo,
                // Keep the user's on/off choice across refreshes.
                enabled = previous[info.id]?.enabled ?: info.enabledByDefault,
            )
        }
        val repo = InstalledRepo(url, manifest.name, manifest.version, builtIn, System.currentTimeMillis())
        if (startedAt != 0L && (removedAt[url] ?: 0) > startedAt) throw SourceSetupException("Removed")
        if (startedAt == 0L) removedAt.remove(url)
        update { s ->
            s.copy(
                repos = s.repos.filterNot { it.url == url } + repo,
                plugins = s.plugins.filterNot { it.repoUrl == url } + installed,
            )
        }
        return repo
    }

    fun setPluginEnabled(repoUrl: String, id: String, enabled: Boolean) =
        update { s -> s.copy(plugins = s.plugins.map { if (it.repoUrl == repoUrl && it.id == id) it.copy(enabled = enabled) else it }) }

    fun removeRepo(url: String) {
        if (url == BUILTIN_REPO) return
        removedAt[url] = System.nanoTime()
        File(codeDir, hash(url)).deleteRecursively()
        update { s -> s.copy(repos = s.repos.filterNot { it.url == url }, plugins = s.plugins.filterNot { it.repoUrl == url }) }
    }

    // ---- storage

    private suspend fun get(url: String): String {
        val r = try {
            http.fetch(FetchRequest(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SourceSetupException("Couldn't reach that link")
        }
        if (r.status !in 200..299) throw SourceSetupException("That link answered ${r.status}")
        return r.text
    }

    private fun update(change: (SourcesState) -> SourcesState) {
        val next = change(_state.value)
        _state.value = next
        runCatching { file.writeText(json.encodeToString(SourcesState.serializer(), next)) }
            .onFailure { Log.w(TAG, "save failed", it) }
    }

    private fun load(): SourcesState =
        runCatching { json.decodeFromString(SourcesState.serializer(), file.readText()) }.getOrDefault(SourcesState())

    private fun codeFile(repoUrl: String, id: String) = File(File(codeDir, hash(repoUrl)), "${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.js")

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        private const val TAG = "ReelSources"
        const val BUILTIN_REPO = "${AppFetcher.ASSET}plugins/manifest.json"
        private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    }
}
