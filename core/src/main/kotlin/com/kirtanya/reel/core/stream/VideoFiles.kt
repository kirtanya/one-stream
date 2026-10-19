package com.kirtanya.reel.core.stream

/** One file inside a torrent. */
data class TorrentFileEntry(val index: Int, val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean get() = extension in VIDEO_EXTENSIONS
    val isSample: Boolean get() = SAMPLE.containsMatchIn(name)

    companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "ogv")
        private val SAMPLE = Regex("""(^|[\W_])sample([\W_]|$)""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Picks the file to stream: the requested index if valid, else the file matching the
 * filename hint, else the biggest non-sample video, else the biggest file.
 */
fun pickStreamFile(files: List<TorrentFileEntry>, fileIdx: Int? = null, filenameHint: String? = null): TorrentFileEntry? {
    if (files.isEmpty()) return null
    fileIdx?.let { idx -> files.firstOrNull { it.index == idx }?.let { return it } }
    filenameHint?.takeIf { it.isNotBlank() }?.let { hint ->
        files.firstOrNull { it.name.equals(hint, ignoreCase = true) }?.let { return it }
        files.firstOrNull { it.path.contains(hint, ignoreCase = true) }?.let { return it }
    }
    return files.filter { it.isVideo && !it.isSample }.maxByOrNull { it.size }
        ?: files.filter { it.isVideo }.maxByOrNull { it.size }
        ?: files.maxByOrNull { it.size }
}

/** MIME type for the local stream server, by file extension. */
fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mp2t"
    "wmv" -> "video/x-ms-wmv"
    "flv" -> "video/x-flv"
    "mpg", "mpeg" -> "video/mpeg"
    "ogv" -> "video/ogg"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a" -> "audio/mp4"
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    else -> "application/octet-stream"
}
