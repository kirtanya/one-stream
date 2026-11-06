package com.kirtanya.reel.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals

class DiskBackendTest {
    @Test fun pixelUsesMmap() = assertEquals(DiskBackend.MMAP, DiskBackend.forDevice(is64 = true, manufacturer = "Google"))

    @Test fun samsungUsesPosixEvenOn64Bit() = assertEquals(DiskBackend.POSIX, DiskBackend.forDevice(is64 = true, manufacturer = "samsung"))

    @Test fun thirtyTwoBitTvUsesPosix() = assertEquals(DiskBackend.POSIX, DiskBackend.forDevice(is64 = false, manufacturer = "Sony"))
}
