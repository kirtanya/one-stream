package com.kirtanya.reel.core.stream

/**
 * Which files of a torrent the user wants, and which one to stream.
 *
 * A torrent's choices accumulate: downloading E2 of a season pack after E1 must keep E1, and a
 * whole-torrent download must never shrink to one file.
 */
data class FileIntent(
    /** The user asked for the whole torrent. */
    val fullTorrent: Boolean = false,
    /** Chosen file indices (season-pack episodes). */
    val wantedFiles: List<Int> = emptyList(),
    /** Filenames chosen before the index was known. */
    val wantedHints: List<String> = emptyList(),
    /** The file streamed last (Play now / Continue watching without an index). */
    val streamFileIndex: Int? = null,
)

/** File priority levels mirrored from libtorrent (0 = don't download, 4 = normal). */
const val PRIO_IGNORE = 0
const val PRIO_DEFAULT = 4

object FileChoice {
    /**
     * The file to stream. In order: the requested index, the filename hint, the file streamed
     * last, the single wanted file, the first wanted hint, then the biggest video that isn't
     * ignored, then the biggest video at all.
     */
    fun pickStream(
        files: List<TorrentFileEntry>,
        fileIdx: Int?,
        hint: String?,
        intent: FileIntent,
        isIgnored: (Int) -> Boolean = { false },
    ): TorrentFileEntry? {
        if (files.isEmpty()) return null
        if (fileIdx != null || !hint.isNullOrBlank()) {
            byIndexOrHint(files, fileIdx, hint)?.let { return it }
        }
        intent.streamFileIndex?.let { idx -> files.firstOrNull { it.index == idx }?.let { return it } }
        intent.wantedFiles.singleOrNull()?.let { idx -> files.firstOrNull { it.index == idx }?.let { return it } }
        intent.wantedHints.firstOrNull()?.let { h -> byIndexOrHint(files, null, h)?.let { return it } }
        val notIgnored = files.filter { !isIgnored(it.index) }
        return notIgnored.filter { it.isVideo && !it.isSample }.maxByOrNull { it.size }
            ?: pickStreamFile(files, null, null)
    }

    private fun byIndexOrHint(files: List<TorrentFileEntry>, idx: Int?, hint: String?): TorrentFileEntry? {
        idx?.let { i -> files.firstOrNull { it.index == i }?.let { return it } }
        hint?.takeIf { it.isNotBlank() }?.let { h ->
            files.firstOrNull { it.name.equals(h, ignoreCase = true) }?.let { return it }
            files.firstOrNull { it.path.contains(h, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /**
     * Adds a download request to an existing intent. Never narrows it: a full-torrent download
     * stays full, earlier episodes stay wanted.
     */
    fun merge(old: FileIntent, onlyFileIndex: Int?, onlyFileHint: String?): FileIntent = when {
        old.fullTorrent -> old
        onlyFileIndex == null && onlyFileHint.isNullOrBlank() -> old.copy(fullTorrent = true)
        onlyFileIndex != null -> old.copy(wantedFiles = (old.wantedFiles + onlyFileIndex).distinct())
        else -> old.copy(wantedHints = (old.wantedHints + onlyFileHint!!).distinct())
    }

    /**
     * Explicit file priorities for a DOWNLOAD (or a kept stream): the full torrent, or just the
     * wanted files. With nothing chosen at all ([keepAllWhenEmpty]) everything is wanted.
     */
    fun explicitPriorities(intent: FileIntent, files: List<TorrentFileEntry>, numFiles: Int, keepAllWhenEmpty: Boolean = true): IntArray {
        if (intent.fullTorrent) return IntArray(numFiles) { PRIO_DEFAULT }
        val wanted = HashSet<Int>()
        wanted += intent.wantedFiles.filter { it in 0 until numFiles }
        intent.wantedHints.forEach { h -> byIndexOrHint(files, null, h)?.let { wanted += it.index } }
        if (wanted.isEmpty()) return IntArray(numFiles) { if (keepAllWhenEmpty) PRIO_DEFAULT else PRIO_IGNORE }
        return IntArray(numFiles) { if (it in wanted) PRIO_DEFAULT else PRIO_IGNORE }
    }

    /** Merges [explicit] into [current] priorities without lowering any file. */
    fun raiseOnly(current: IntArray, explicit: IntArray): IntArray =
        IntArray(maxOf(current.size, explicit.size)) { i -> maxOf(current.getOrElse(i) { 0 }, explicit.getOrElse(i) { 0 }) }
}
