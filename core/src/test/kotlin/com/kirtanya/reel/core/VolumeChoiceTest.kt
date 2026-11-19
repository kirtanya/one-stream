package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.NotConnectedReason
import com.kirtanya.reel.core.homedisk.VolumeChoice
import com.kirtanya.reel.core.homedisk.VolumeFacts
import com.kirtanya.reel.core.homedisk.VolumeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VolumeChoiceTest {
    private val gb = 1_000_000_000L
    private val internal = VolumeFacts("emulated", "Internal storage", removable = false, emulated = true, primary = true, state = VolumeState.MOUNTED, usable = true, freeBytes = 5 * gb, totalBytes = 8 * gb)
    private fun usb(id: String = "37EE-1DEE", name: String = "PiBOX", free: Long = 477 * gb, total: Long = 512 * gb, state: VolumeState = VolumeState.MOUNTED, usable: Boolean = true) =
        VolumeFacts(id, name, removable = true, emulated = false, primary = false, state = state, usable = usable, freeBytes = free, totalBytes = total)

    @Test fun `the internal storage is never the home disk`() {
        assertNull(VolumeChoice.pick(listOf(internal), null))
        assertEquals("37EE-1DEE", VolumeChoice.pick(listOf(internal, usb()), null)?.id)
    }

    @Test fun `a plugged disk is ready with its name and space`() {
        val s = VolumeChoice.state(listOf(internal, usb()), null)
        assertIs<HomeDiskState.Connected>(s)
        assertEquals("PiBOX", s.name)
        assertEquals("USB", s.protocol)
        assertEquals(477 * gb, s.freeBytes)
        assertEquals(512 * gb, s.totalBytes)
    }

    @Test fun `nothing plugged in is a normal state`() {
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.NO_USB), VolumeChoice.state(listOf(internal), null))
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.NO_USB), VolumeChoice.state(emptyList(), "37EE-1DEE"))
    }

    @Test fun `a disk the TV can't mount is called out`() {
        val s = VolumeChoice.state(listOf(internal, usb(state = VolumeState.UNMOUNTABLE, usable = false)), null)
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.UNREADABLE), s)
    }

    @Test fun `a disk that is mounted but has no app folder is not used`() {
        assertNull(VolumeChoice.pick(listOf(usb(usable = false)), null))
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.NO_USB), VolumeChoice.state(listOf(usb(usable = false)), null))
    }

    @Test fun `a disk with less than the floor free is full`() {
        val s = VolumeChoice.state(listOf(usb(free = VolumeChoice.MIN_FREE - 1)), null)
        assertEquals(HomeDiskState.NotConnected(NotConnectedReason.FULL, "PiBOX"), s)
        assertIs<HomeDiskState.Connected>(VolumeChoice.state(listOf(usb(free = VolumeChoice.MIN_FREE)), null))
    }

    @Test fun `the remembered disk wins over a bigger one`() {
        val small = usb(id = "AAAA-0001", name = "Small", total = 64 * gb, free = 60 * gb)
        val big = usb(id = "BBBB-0002", name = "Big")
        assertEquals("AAAA-0001", VolumeChoice.pick(listOf(big, small), "AAAA-0001")?.id)
        assertEquals("BBBB-0002", VolumeChoice.pick(listOf(big, small), null)?.id)
        assertEquals("BBBB-0002", VolumeChoice.pick(listOf(big, small), "CCCC-0003")?.id)
    }

    @Test fun `the same size is settled by free space, then by id, so it never flips`() {
        val a = usb(id = "AAAA-0001", free = 100 * gb)
        val b = usb(id = "BBBB-0002", free = 300 * gb)
        assertEquals("BBBB-0002", VolumeChoice.pick(listOf(a, b), null)?.id)
        assertEquals("BBBB-0002", VolumeChoice.pick(listOf(b, a), null)?.id)
        val c = usb(id = "CCCC-0003", free = 300 * gb)
        assertEquals("BBBB-0002", VolumeChoice.pick(listOf(c, b), null)?.id)
        assertEquals("BBBB-0002", VolumeChoice.pick(listOf(b, c), null)?.id)
    }
}
