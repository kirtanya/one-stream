package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.FatNames
import com.kirtanya.reel.core.homedisk.SplitMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FatNamesTest {
    @Test fun `an ordinary name is left alone`() {
        assertEquals("Movie (2020).mkv", FatNames.safe("Movie (2020).mkv"))
    }

    @Test fun `characters FAT forbids are removed`() {
        val out = FatNames.safe("a:b*c?d\"e<f>g|h.mkv")
        assertTrue(out.none { it in ":*?\"<>|" }, out)
        assertTrue(out.endsWith(".mkv"), out)
    }

    @Test fun `Windows device names get an underscore, with any case and extension`() {
        assertEquals("CON_.mkv", FatNames.safe("CON.mkv"))
        assertEquals("nul_", FatNames.safe("nul"))
        assertEquals("Com1_.txt", FatNames.safe("Com1.txt"))
        assertEquals("lpt9_.a.b", FatNames.safe("lpt9.a.b"))
    }

    @Test fun `names that only start like a device are fine`() {
        assertEquals("console.mkv", FatNames.safe("console.mkv"))
        assertEquals("com10.mkv", FatNames.safe("com10.mkv"))
    }

    @Test fun `a name that looks like a split piece is escaped`() {
        assertEquals("a.7z.001_", FatNames.safe("a.7z.001"))
        assertNull(SplitMath.parsePiece(FatNames.safe("a.7z.001")))
    }

    @Test fun `a name that looks like a part file is escaped`() {
        assertEquals("x.part_", FatNames.safe("x.part"))
        assertEquals("x.PART_", FatNames.safe("x.PART"))
    }

    @Test fun `a safe name plus piece and part suffixes fits in 255 characters`() {
        val long = FatNames.safe("a".repeat(400) + ".mkv")
        assertTrue((long + ".001.part").length <= 255, "${long.length}")
    }

    @Test fun `escaping is stable`() {
        for (n in listOf("CON.mkv", "a.7z.001", "x.part", "plain.mkv")) {
            assertEquals(FatNames.safe(n), FatNames.safe(FatNames.safe(n)))
        }
    }
}
