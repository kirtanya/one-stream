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
import com.kirtanya.reel.core.sync.ApplyResult
import com.kirtanya.reel.core.sync.PairedPhone
import com.kirtanya.reel.core.sync.SyncCrypto
import com.kirtanya.reel.core.sync.SyncProtocol
import com.kirtanya.reel.core.sync.TvCredentials
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLinksTest {
    @Test fun severalLinksOnePerLine() = assertEquals(
        listOf(
            "https://torrentio.strem.fun/manifest.json",
            "https://opensubtitles-v3.strem.io/manifest.json",
            "https://addon.example/base/manifest.json",
        ),
        SourceLinks.allFromUserInput(
            "https://torrentio.strem.fun/manifest.json\r\n\nstremio://opensubtitles-v3.strem.io/manifest.json  \n https://addon.example/base/\n",
        ),
    )

    @Test fun severalLinksDropDuplicatesAndNonLinks() = assertEquals(
        listOf("https://a.example/manifest.json", "https://b.example/manifest.json"),
        SourceLinks.allFromUserInput("https://a.example/manifest.json hello https://b.example/manifest.json, stremio://a.example/manifest.json"),
    )

    @Test fun oneLinkStaysOne() = assertEquals(
        listOf("https://a.example/manifest.json"),
        SourceLinks.allFromUserInput("  https://a.example/manifest.json  "),
    )

    @Test fun noLinks() = assertEquals(emptyList(), SourceLinks.allFromUserInput("just some words"))

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

    @Test fun punctuationAndQuotes() {
        assertEquals("https://a.example/manifest.json", SourceLinks.extract("“https://a.example/manifest.json”"))
        assertEquals("https://a.example/manifest.json", SourceLinks.extract("add https://a.example/manifest.json; thanks!"))
    }

    @Test fun bareBaseUrlFromTheSettingsField() {
        assertEquals("https://watchhub.strem.io/manifest.json", SourceLinks.fromUserInput("https://watchhub.strem.io/"))
        assertNull(SourceLinks.fromUserInput("not a link"))
    }

    @Test fun nestedAddonParamsAreBounded() {
        var link = "https://x.example/manifest.json"
        repeat(10) { link = "https://web.stremio.com/#/addons?addon=" + java.net.URLEncoder.encode(link, Charsets.UTF_8) }
        assertNull(SourceLinks.normalize(link))
        assertNull(SourceLinks.extract("https://a/" + "x".repeat(20_000) + "/manifest.json"))
    }

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
        assertEquals("1 addon added, 1 addon removed, 1 plugin repo added, 1 plugin repo removed, 2 switched on/off", plan.describe())
    }

    @Test fun builtInRepoIsNeverRemoved() {
        val plan = SyncPlan.between(SourcesBundle(repos = listOf(BundleRepo(builtIn))), SourcesBundle(), setOf(builtIn))
        assertTrue(plan.isEmpty)
        assertEquals("already up to date", plan.describe())
    }
}

/** Real HTTP on localhost: pairing window + code, verified and encrypted pushes. */
class SyncServerTest {
    private class FakeTv(override val tvId: String = "tv-1") : SyncHost {
        override val deviceName = "Living room TV"
        var applied: SourcesBundle? = null
        val phones = mutableListOf<PairedPhone>()
        override suspend fun apply(bundle: SourcesBundle): ApplyResult { applied = bundle; return ApplyResult("+${bundle.addons.size} addons") }
        override fun pairedPhones() = phones
        override fun savePairedPhone(phone: PairedPhone) { phones += phone }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private val client = SyncClient(OkHttpFetcher(OkHttpClient()))

    private fun <T> withServer(tv: FakeTv, clock: () -> Long = System::currentTimeMillis, block: suspend (SyncServer, String) -> T): T {
        val port = freePort()
        val server = SyncServer(tv, port, clock).apply { start(5_000, true) }
        try {
            return runBlocking { block(server, "http://127.0.0.1:$port") }
        } finally {
            server.stop()
        }
    }

    @Test fun pairingOnlyWhileTheTvShowsTheCode() = withServer(FakeTv()) { server, base ->
        val closed = assertFailsWith<SyncException> { client.pair(base, "123456", "Pixel 8") }
        assertTrue(closed.message!!.contains("Pairing is closed"))
        server.openPairing()
        assertEquals(6, server.code.length)
        val paired = client.pair(base, server.code, "Pixel 8")
        assertEquals("tv-1", paired.tvId)
        assertEquals("", server.code) // closes after pairing
        assertFailsWith<SyncException> { client.pair(base, "000000", "Other") }
    }

    @Test fun threeWrongCodesCloseTheWindow() = withServer(FakeTv()) { server, base ->
        server.openPairing()
        val right = server.code
        val wrong = if (right == "111111") "222222" else "111111"
        repeat(2) { assertEquals("Wrong code", assertFailsWith<SyncException> { client.pair(base, wrong, "x") }.message) }
        assertTrue(assertFailsWith<SyncException> { client.pair(base, wrong, "x") }.message!!.contains("Too many"))
        assertFailsWith<SyncException> { client.pair(base, right, "x") } // window is closed now
    }

    @Test fun windowExpires() {
        var now = 1_000_000L
        withServer(FakeTv(), clock = { now }) { server, base ->
            server.openPairing()
            now += SyncProtocol.PAIRING_WINDOW_MS + 1
            assertTrue(assertFailsWith<SyncException> { client.pair(base, server.code, "x") }.message!!.contains("closed"))
        }
    }

    @Test fun verifiedEncryptedPush() {
        val tv = FakeTv()
        withServer(tv) { server, base ->
            server.openPairing()
            val p = client.pair(base, server.code, "Pixel 8")
            val creds = TvCredentials(p.tvId, p.phoneId, p.secret)
            val result = client.push(base, creds, SourcesBundle(addons = listOf(BundleAddon("https://x/manifest.json"))))
            assertEquals("+1 addons", result.summary)
            assertEquals("Pixel 8", tv.applied!!.from)
            // Wrong secret → refused, nothing applied.
            tv.applied = null
            assertFailsWith<SyncException> { client.push(base, creds.copy(secret = "00".repeat(32)), SourcesBundle()) }
            assertNull(tv.applied)
        }
    }

    @Test fun neverSendsToAnImpostor() {
        val real = FakeTv("tv-real")
        val creds = withServer(real) { server, base ->
            server.openPairing()
            client.pair(base, server.code, "Pixel 8").let { TvCredentials(it.tvId, it.phoneId, it.secret) }
        }
        // Another Reel TV (or anything claiming the name) at the remembered address.
        val impostor = FakeTv("tv-real") // even copying the TV's id doesn't help: it can't answer the challenge
        withServer(impostor) { _, base ->
            val e = assertFailsWith<SyncException> { client.push(base, creds, SourcesBundle(addons = listOf(BundleAddon("https://secret-key/manifest.json")))) }
            assertTrue(e.needsPairing || e.message!!.contains("isn't your paired TV"))
            assertNull(impostor.applied)
        }
    }

    @Test fun cryptoRoundTripAndTamper() {
        val secret = SyncCrypto.randomHex(32)
        val box = SyncCrypto.encrypt(secret, "hello".toByteArray(), "n1")
        assertEquals("hello", SyncCrypto.decrypt(secret, box, "n1").decodeToString())
        assertFailsWith<Exception> { SyncCrypto.decrypt(secret, box, "n2") }
        assertFailsWith<Exception> { SyncCrypto.decrypt(SyncCrypto.randomHex(32), box, "n1") }
    }
}
