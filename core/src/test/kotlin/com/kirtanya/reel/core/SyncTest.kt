package com.kirtanya.reel.core

import com.kirtanya.reel.core.sources.OkHttpFetcher
import com.kirtanya.reel.core.sync.BundleAddon
import com.kirtanya.reel.core.sync.BundleRepo
import com.kirtanya.reel.core.sync.LinkKind
import com.kirtanya.reel.core.sync.SourceLinks
import com.kirtanya.reel.core.sync.SourcesBundle
import com.kirtanya.reel.core.sync.SyncClient
import com.kirtanya.reel.core.sync.SyncException
import com.kirtanya.reel.core.sync.SyncHost
import com.kirtanya.reel.core.sync.SyncPlan
import com.kirtanya.reel.core.sync.SyncServer
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLinksTest {
    @Test fun stremioScheme() = assertEquals(
        "https://opensubtitles-v3.strem.io/manifest.json",
        SourceLinks.normalize("stremio://opensubtitles-v3.strem.io/manifest.json"),
    )

    @Test fun webInstallLink() = assertEquals(
        "https://watchhub.strem.io/manifest.json",
        SourceLinks.normalize("https://web.stremio.com/#/addons?addon=https%3A%2F%2Fwatchhub.strem.io%2Fmanifest.json"),
    )

    @Test fun configuredAddonKeepsItsPath() = assertEquals(
        "https://addon.example/abc%3Dconfig/manifest.json",
        SourceLinks.normalize("stremio://addon.example/abc%3Dconfig/manifest.json"),
    )

    @Test fun textWithALink() = assertEquals(
        "https://raw.example.com/me/repo/main/manifest.json",
        SourceLinks.extract("Try my plugins: https://raw.example.com/me/repo/main/manifest.json."),
    )

    @Test fun notAManifest() {
        assertNull(SourceLinks.normalize("https://example.com/page"))
        assertNull(SourceLinks.extract("magnet:?xt=urn:btih:abc"))
        assertNull(SourceLinks.normalize("ftp://x/manifest.json"))
    }

    @Test fun kinds() {
        assertEquals(LinkKind.ADDON, SourceLinks.detectKind("""{"id":"a","name":"A","resources":["stream"],"types":["movie"]}"""))
        assertEquals(LinkKind.PLUGIN_REPO, SourceLinks.detectKind("""{"name":"R","scrapers":[]}"""))
        assertEquals(LinkKind.UNKNOWN, SourceLinks.detectKind("<html>"))
    }
}

class SyncPlanTest {
    private val builtIn = "asset://plugins/manifest.json"

    @Test fun mirrorsThePhone() {
        val tv = SourcesBundle(
            addons = listOf(BundleAddon("a", true), BundleAddon("old", true), BundleAddon("c", true)),
            repos = listOf(BundleRepo(builtIn, mapOf("open" to true, "ia" to true)), BundleRepo("tvonly", mapOf("x" to true))),
        )
        val phone = SourcesBundle(
            addons = listOf(BundleAddon("a", true), BundleAddon("c", false), BundleAddon("new", true)),
            repos = listOf(BundleRepo(builtIn, mapOf("open" to true, "ia" to false)), BundleRepo("r2", mapOf("y" to true))),
        )
        val plan = SyncPlan.between(tv, phone, setOf(builtIn))
        assertEquals(listOf("new"), plan.addAddons.map { it.url })
        assertEquals(listOf("old"), plan.removeAddons)
        assertEquals(mapOf("c" to false), plan.toggleAddons)
        assertEquals(listOf("r2"), plan.addRepos.map { it.url })
        assertEquals(listOf("tvonly"), plan.removeRepos)
        assertEquals(mapOf(builtIn to mapOf("ia" to false)), plan.togglePlugins)
        assertEquals("+1 addon, −1 addon, +1 plugin repo, −1 plugin repo, 2 switched", plan.describe())
    }

    @Test fun builtInRepoIsNeverRemoved() {
        val plan = SyncPlan.between(SourcesBundle(repos = listOf(BundleRepo(builtIn))), SourcesBundle(), setOf(builtIn))
        assertTrue(plan.isEmpty)
        assertEquals("already up to date", plan.describe())
    }
}

/** Real HTTP on localhost: pair with the code, push with the token. */
class SyncServerTest {
    private class FakeTv : SyncHost {
        override val deviceName = "Living room TV"
        var applied: SourcesBundle? = null
        val tokens = mutableMapOf<String, String>()
        override fun currentBundle() = SourcesBundle()
        override suspend fun apply(bundle: SourcesBundle): String { applied = bundle; return "+${bundle.addons.size} addons" }
        override fun pairedTokens() = tokens
        override fun savePairedToken(token: String, device: String) { tokens[token] = device }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun pairThenPush(): Unit = runBlocking {
        val tv = FakeTv()
        val port = freePort()
        val server = SyncServer(tv, port).apply { start(5_000, true) }
        try {
            val client = SyncClient(OkHttpFetcher(OkHttpClient()))
            val base = "http://127.0.0.1:$port"
            assertEquals("Living room TV", client.hello(base).name)

            val unpaired = assertFailsWith<SyncException> { client.push(base, "nope", SourcesBundle()) }
            assertTrue(unpaired.message!!.contains("Pair again"))
            assertFailsWith<SyncException> { client.pair(base, if (server.code == "1234") "4321" else "1234", "Pixel 8") }

            val codeBefore = server.code
            val paired = client.pair(base, codeBefore, "Pixel 8")
            assertEquals("Living room TV", paired.name)
            assertEquals("Pixel 8", tv.tokens[paired.token])

            val summary = client.push(base, paired.token, SourcesBundle(addons = listOf(BundleAddon("https://x/manifest.json"))))
            assertEquals("+1 addons", summary)
            assertEquals("Pixel 8", tv.applied!!.from) // the server stamps who sent it
        } finally {
            server.stop()
        }
    }

    @Test fun fiveWrongCodesLockPairing(): Unit = runBlocking {
        val tv = FakeTv()
        val port = freePort()
        var now = 1_000_000L
        val server = SyncServer(tv, port, clock = { now }).apply { start(5_000, true) }
        try {
            val client = SyncClient(OkHttpFetcher(OkHttpClient()))
            val base = "http://127.0.0.1:$port"
            val wrong = { if (server.code == "1111") "2222" else "1111" }
            repeat(5) { assertFailsWith<SyncException> { client.pair(base, wrong(), "x") } }
            val locked = assertFailsWith<SyncException> { client.pair(base, server.code, "x") }
            assertTrue(locked.message!!.contains("Too many"))
            now += 61_000
            assertEquals("Living room TV", client.pair(base, server.code, "x").name)
        } finally {
            server.stop()
        }
    }
}
