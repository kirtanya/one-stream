package com.kirtanya.reel.core

import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.core.magnet.buildMagnet
import com.kirtanya.reel.core.magnet.normalizeInfoHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MagnetTest {
    private val bbb = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"

    @Test fun parsesHexMagnetWithNameAndTrackers() {
        val m = Magnet.parse(
            "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&dn=Big+Buck+Bunny" +
                "&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Fexplodie.org%3A6969&xl=276445467",
        )!!
        assertEquals(bbb, m.infoHash)
        assertEquals("Big Buck Bunny", m.name)
        assertEquals(listOf("udp://explodie.org:6969"), m.trackers)
        assertEquals(276445467L, m.sizeBytes)
    }

    @Test fun parsesBase32Hash() {
        assertEquals(bbb, Magnet.parse("magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4")!!.infoHash)
    }

    @Test fun rejectsNonMagnetsAndBadHashes() {
        assertNull(Magnet.parse("https://example.com/file.torrent"))
        assertNull(Magnet.parse("magnet:?dn=no-hash"))
        assertNull(Magnet.parse("magnet:?xt=urn:btih:1234"))
        assertNull(normalizeInfoHash("zz8255ecdc7ca55fb0bbf81323d87062db1f6d1c"))
    }

    @Test fun buildRoundTrips() {
        val uri = buildMagnet(bbb.uppercase(), "Big Buck Bunny", listOf("udp://a:1", "udp://a:1", "wss://b"))
        val m = Magnet.parse(uri)!!
        assertEquals(bbb, m.infoHash)
        assertEquals("Big Buck Bunny", m.name)
        assertEquals(listOf("udp://a:1", "wss://b"), m.trackers)
    }
}
