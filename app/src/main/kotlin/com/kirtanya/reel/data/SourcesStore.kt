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
        stale.forEach { repo -> runCatching { addRepo(repo.url, repo.builtIn) }.onFailure { Log.w(TAG, "refresh ${repo.url}", it) } }
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

    // ---- addons

    suspend fun addAddon(input: String): InstalledAddon = mutex.withLock {
        val url = StremioAddon.manifestUrl(input)
        val body = get(url)
        val manifest = try {
            StremioAddon.parseManifest(body)
        } catch (e: Exception) {
            throw SourceSetupException("That link isn't a Stremio addon")
        }
        if (!manifest.hasStreams && !manifest.hasSubtitles) throw SourceSetupException("${manifest.name} doesn't provide streams or subtitles")
        val addon = InstalledAddon(url, body)
        update { s -> s.copy(addons = s.addons.filterNot { it.url == url } + addon) }
        addon
    }

    fun setAddonEnabled(url: String, enabled: Boolean) =
        update { s -> s.copy(addons = s.addons.map { if (it.url == url) it.copy(enabled = enabled) else it }) }

    fun removeAddon(url: String) = update { s -> s.copy(addons = s.addons.filterNot { it.url == url }) }

    // ---- plugin repos

    suspend fun addRepo(input: String, builtIn: Boolean = false): InstalledRepo = mutex.withLock {
        val url = input.trim()
        val manifest = try {
            NuvioPlugins.parseManifest(get(url))
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
        update { s ->
            s.copy(
                repos = s.repos.filterNot { it.url == url } + repo,
                plugins = s.plugins.filterNot { it.repoUrl == url } + installed,
            )
        }
        repo
    }

    fun setPluginEnabled(repoUrl: String, id: String, enabled: Boolean) =
        update { s -> s.copy(plugins = s.plugins.map { if (it.repoUrl == repoUrl && it.id == id) it.copy(enabled = enabled) else it }) }

    fun removeRepo(url: String) {
        if (url == BUILTIN_REPO) return
        File(codeDir, hash(url)).deleteRecursively()
        update { s -> s.copy(repos = s.repos.filterNot { it.url == url }, plugins = s.plugins.filterNot { it.repoUrl == url }) }
    }

    // ---- storage

    private suspend fun get(url: String): String {
        val r = try {
            http.fetch(FetchRequest(url))
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
