package com.kirtanya.reel.core.homedisk

/**
 * Where everything lives on the home disk (docs/HOME_DISK.md §3.1). Paths are relative to the share and
 * use `/`. `library/` is laid out for people (the Mac browses it), everything else is One Stream's own.
 */
object DiskLayout {
    const val ROOT = "OneStream"
    const val META = ".onestream"
    const val MAX_COMPONENT = 120
    const val MAX_PATH = 240

    val diskJson = "$ROOT/$META/disk.json"
    val testFile = "$ROOT/$META/test.bin"
    val readme = "$ROOT/README.txt"
    val library = "$ROOT/library"

    fun incoming(key: String) = "$ROOT/incoming/${checkKey(key)}"
    fun cache(key: String) = "$ROOT/cache/${checkKey(key)}"
    /** [stamp] = yyyyMMddHHmm of the delete. */
    fun trash(key: String, stamp: String) = "$ROOT/.trash/${checkKey(key)}-$stamp"
    fun manifest(dir: String) = "$dir/$META/manifest.json"
    fun parts(dir: String, fileIndex: Int) = "$dir/$META/parts/$fileIndex.parts"
    fun lease(dir: String) = "$dir/$META/lease.json"
    /** A finished title's manifest sits in its library folder, one per torrent key. */
    fun libraryManifest(titleFolder: String, key: String) = "$titleFolder/$META/${checkKey(key)}.json"

    /**
     * The library folder for a title: `Movies/<Title> (<Year>)`, `Shows/<Show> (<Year>)/Season <NN>`, or
     * `Other/<torrent name>` when the download has no TMDB origin (a pasted magnet).
     */
    fun titleFolder(name: LibraryName?, torrentName: String): String {
        if (name == null || name.title.isBlank()) return "$library/Other/${clean(torrentName)}"
        val base = clean(name.title + (name.year?.let { " ($it)" } ?: ""))
        return when {
            name.isShow && name.season != null -> "$library/Shows/$base/Season ${"%02d".format(name.season)}"
            name.isShow -> "$library/Shows/$base"
            else -> "$library/Movies/$base"
        }
    }

    /**
     * A library file path that fits the router's file system: the file name is cut (before its extension)
     * until the whole path is ≤ [MAX_PATH]; a folder too long on its own gets cut too.
     */
    fun libraryFile(titleFolder: String, fileName: String): String {
        val file = clean(fileName)
        val full = "$titleFolder/$file"
        if (full.length <= MAX_PATH) return full
        val room = MAX_PATH - titleFolder.length - 1
        if (room >= 16) return "$titleFolder/${cutKeepingExtension(file, room)}"
        // The folder itself is too long: cut its last component, then fit the file in what is left.
        val parent = titleFolder.substringBeforeLast('/')
        val last = titleFolder.substringAfterLast('/')
        val folder = "$parent/${last.take(maxOf(8, MAX_PATH / 2 - parent.length - 1)).trimEnd('.', ' ')}"
        return "$folder/${cutKeepingExtension(file, maxOf(16, MAX_PATH - folder.length - 1))}"
    }

    /**
     * One path component made safe for SMB file systems (§3.1): `< > : " / \ | ? *` and control characters
     * become a space, runs of spaces collapse, trailing dots and spaces go, at most [MAX_COMPONENT]
     * characters (cut before the extension). Never empty.
     */
    fun clean(name: String): String {
        val replaced = buildString(name.length) {
            for (c in name) append(if (c < ' ' || c == '\u007f' || c in "<>:\"/\\|?*") ' ' else c)
        }
        val collapsed = replaced.replace(Regex(" {2,}"), " ").trim().trimEnd('.', ' ').trimStart('.')
        val cut = cutKeepingExtension(collapsed, MAX_COMPONENT).trimEnd('.', ' ')
        return cut.ifEmpty { "Untitled" }
    }

    /**
     * [wanted] unless another torrent key already uses that name (router file systems ignore case);
     * then ` (2)`, ` (3)`, … [takenByOthers] holds names used by other keys.
     */
    fun unique(wanted: String, takenByOthers: Collection<String>): String {
        val taken = takenByOthers.mapTo(HashSet()) { it.lowercase() }
        if (wanted.lowercase() !in taken) return wanted
        var n = 2
        while ("$wanted ($n)".lowercase() in taken) n++
        return "$wanted ($n)"
    }

    private fun cutKeepingExtension(name: String, max: Int): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot in 2..8) name.substring(dot) else ""
        return name.take(maxOf(1, max - ext.length)).trimEnd('.', ' ') + ext
    }

    private fun checkKey(key: String): String {
        require(key.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "not a torrent key" }
        return key
    }
}

/** What a library folder is named after (from the download's TMDB origin). */
data class LibraryName(val title: String, val year: Int? = null, val isShow: Boolean = false, val season: Int? = null)
