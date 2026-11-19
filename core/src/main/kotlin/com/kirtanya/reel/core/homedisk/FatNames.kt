package com.kirtanya.reel.core.homedisk

/**
 * File names on the TV's FAT32 disk (docs/HOME_DISK.md §23.4). [DiskLayout.clean] already removes the
 * characters FAT forbids and caps a component at 120 characters, so the 255-character limit holds even with
 * `.001.part` added. This adds what only this backend needs: names Windows can't open (the disk may be plugged
 * into a PC) and names that could be mistaken for a split piece or a part file.
 */
object FatNames {
    private val DEVICE = (listOf("con", "prn", "aux", "nul") + (1..9).map { "com$it" } + (1..9).map { "lpt$it" }).toSet()
    private val PIECE_LIKE = Regex(".*\\.\\d{3}")

    /** One path component, safe to store. `CON.mkv` → `CON_.mkv`; `a.7z.001` → `a.7z.001_`; `x.part` → `x.part_`. */
    fun safe(name: String): String {
        val clean = DiskLayout.clean(name)
        val stem = clean.substringBefore('.')
        val noDevice = if (stem.lowercase() in DEVICE) stem + "_" + clean.removePrefix(stem) else clean
        return if (PIECE_LIKE.matches(noDevice) || noDevice.lowercase().endsWith(SplitMath.PART)) "${noDevice}_" else noDevice
    }
}
