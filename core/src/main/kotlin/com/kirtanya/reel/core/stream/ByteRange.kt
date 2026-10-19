package com.kirtanya.reel.core.stream

/** An inclusive byte range inside a file. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1

    companion object {
        /**
         * Parses an HTTP `Range` header (single range only) against a file of [fileSize] bytes.
         * Returns the whole file when the header is missing, and null when it can't be satisfied
         * (the server should answer 416).
         */
        fun parse(header: String?, fileSize: Long): ByteRange? {
            if (fileSize <= 0) return null
            if (header.isNullOrBlank()) return ByteRange(0, fileSize - 1)
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true)) return null
            val first = spec.substring(6).split(',').first().trim()
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val startText = first.substring(0, dash).trim()
            val endText = first.substring(dash + 1).trim()
            return when {
                startText.isEmpty() -> {
                    // Suffix range: last N bytes.
                    val n = endText.toLongOrNull() ?: return null
                    if (n <= 0) return null
                    ByteRange(maxOf(0, fileSize - n), fileSize - 1)
                }
                else -> {
                    val start = startText.toLongOrNull() ?: return null
                    if (start < 0 || start >= fileSize) return null
                    val end = endText.toLongOrNull()?.coerceAtMost(fileSize - 1) ?: (fileSize - 1)
                    if (end < start) return null
                    ByteRange(start, end)
                }
            }
        }
    }
}
