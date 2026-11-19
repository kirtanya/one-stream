package com.kirtanya.reel.core.homedisk

/** Paths of downloads on the TV's USB volume (docs/HOME_DISK.md §24). */
object UsbPaths {
    private val NOT_REMOVABLE = setOf("emulated", "self")

    /** `/storage/37EE-1DEE/Android/…` → `37EE-1DEE`; internal storage and anything else → null. */
    fun volumeIdOf(path: String): String? {
        if (!path.startsWith("/storage/")) return null
        val id = path.removePrefix("/storage/").substringBefore('/')
        return id.takeIf { it.isNotEmpty() && it !in NOT_REMOVABLE }
    }

    /** The path is on a USB volume that is not among [mountedIds]. */
    fun isMissing(path: String, mountedIds: Set<String>): Boolean = volumeIdOf(path)?.let { it !in mountedIds } == true

    private fun needsFix(c: String) = c.any { it < ' ' || it == '\u007f' || it in "<>:\"\\|?*" } || c.endsWith('.') || c.endsWith(' ') || c.length > 255

    /**
     * File paths (relative, `/` separated) a FAT file system would refuse become [DiskLayout.clean]ed, unique within the list
     * (FAT ignores case). Returns index → new path for the paths that change; the rest are not touched.
     */
    fun fatSafePaths(paths: List<String>): Map<Int, String> {
        val fixed = paths.map { p -> p.split('/').joinToString("/") { if (needsFix(it)) DiskLayout.clean(it) else it } }
        val taken = HashSet<String>()
        paths.forEachIndexed { i, p -> if (fixed[i] == p) taken += p.lowercase() }
        val out = LinkedHashMap<Int, String>()
        fixed.forEachIndexed { i, f ->
            if (f == paths[i]) return@forEachIndexed
            var candidate = f
            var n = 2
            while (candidate.lowercase() in taken) {
                val slash = candidate.lastIndexOf('/') + 1
                val name = f.substring(slash)
                val dot = name.lastIndexOf('.')
                val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
                candidate = f.substring(0, slash) + "$stem ($n)$ext"
                n++
            }
            taken += candidate.lowercase()
            out[i] = candidate
        }
        return out
    }
}
