package com.kirtanya.reel.ui

fun formatBytes(bytes: Long): String = com.kirtanya.reel.core.sources.ByteSize.format(bytes)

fun formatEta(seconds: Long?): String = when {
    seconds == null -> "—"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

/** "1 peer", "3 peers". */
fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
