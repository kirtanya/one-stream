package com.kirtanya.reel.core.sources

import java.util.Locale

/** "9.8 GB", "412 MB": file sizes as every screen (the Sources page, Downloads, the player's source list) shows them. */
object ByteSize {
    fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }
}
