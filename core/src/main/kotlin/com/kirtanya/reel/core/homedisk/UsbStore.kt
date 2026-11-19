package com.kirtanya.reel.core.homedisk

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * [HomeStore] over a folder on the TV's USB disk (docs/HOME_DISK.md §23). A file bigger than [pieceSize] is stored as
 * `name.001`, `name.002`, … (FAT32 can't hold 4 GiB), a part file as `name.part` / `name.001.part`; reads cut at
 * piece boundaries ([SplitMath]) so callers see one stream. [pieceSize] is a parameter only so tests (and the disk
 * Test) can split tiny files; the app always uses [SplitMath.PIECE_SIZE].
 *
 * Listing rule: a split file is listed only when piece 001 exists and every later piece is there with full size
 * (the last one non-empty). Commit renames the last piece first and 001 last, so 001 is the commit mark and a
 * cut in the middle never shows a half file.
 */
class UsbStore(private val root: File, private val pieceSize: Long = SplitMath.PIECE_SIZE) : HomeStore {
    /** Open write handles by physical file, so many small writes aren't an open/close each. */
    private val writers = HashMap<File, RandomAccessFile>()

    override fun freeBytes(): Long? = root.usableSpace.takeIf { root.exists() }
    override fun totalBytes(): Long? = root.totalSpace.takeIf { root.exists() }

    override fun exists(path: String) = file(path).isDirectory || size(path) != null

    override fun size(path: String): Long? = pieces(file(path))?.let { it.sumOf { f -> f.length() } }

    override fun list(dir: String): List<StoreEntry> {
        val d = file(dir)
        val names = d.list()?.toList() ?: return emptyList()
        val out = ArrayList<StoreEntry>()
        val pieceBases = HashSet<String>()
        for (n in names) {
            val f = File(d, n)
            when {
                f.isDirectory -> out += StoreEntry(n, true)
                n.endsWith(SplitMath.PART) -> Unit
                SplitMath.parsePiece(n) != null -> SplitMath.parsePiece(n)?.first?.let(pieceBases::add)
                else -> out += StoreEntry(n, false, f.length())
            }
        }
        for (base in pieceBases) {
            if (out.any { it.name == base }) continue
            pieces(File(d, base))?.let { out += StoreEntry(base, false, it.sumOf { f -> f.length() }) }
        }
        return out.sortedBy { it.name }
    }

    override fun read(path: String, pos: Long, buf: ByteArray, off: Int, len: Int, part: Boolean): Int {
        val target = file(path)
        val dir = target.parentFile ?: return -1
        val suffix = if (part) SplitMath.PART else ""
        val whole = File(dir, target.name + suffix)
        if (whole.isFile) return readRun(whole, pos, buf, off, len, whole.length())
        // A committed split file must be complete; a part file may have any piece missing so far (writes are out of order).
        val committed = if (part) null else pieces(target) ?: return -1
        val total = committed?.sumOf { it.length() } ?: Long.MAX_VALUE
        var done = 0
        for (run in SplitMath.runs(pos, len, total, pieceSize)) {
            val f = committed?.get(run.piece) ?: File(dir, SplitMath.pieceName(target.name, run.piece) + suffix)
            if (!f.isFile) break
            val n = readRun(f, run.local, buf, off + done, run.length, f.length())
            if (n <= 0) break
            done += n
            if (n < run.length) break
        }
        return if (done == 0) -1 else done
    }

    private fun readRun(f: File, local: Long, buf: ByteArray, off: Int, len: Int, limit: Long): Int {
        if (local >= limit || len == 0) return -1
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(local)
            var done = 0
            val want = minOf(len.toLong(), limit - local).toInt()
            while (done < want) {
                val n = raf.read(buf, off + done, want - done)
                if (n < 0) break
                done += n
            }
            return if (done == 0) -1 else done
        }
    }

    @Synchronized
    override fun write(path: String, totalSize: Long, pos: Long, buf: ByteArray, off: Int, len: Int) {
        require(pos >= 0 && pos + len <= totalSize) { "write outside the file" }
        val target = file(path)
        target.parentFile?.mkdirs()
        val split = SplitMath.isSplit(totalSize, pieceSize)
        // A zero-byte file has no runs but must still exist after commit, so it gets its (empty) part file here.
        if (totalSize == 0L) {
            val empty = File(target.parentFile, target.name + SplitMath.PART)
            writers.getOrPut(empty) { RandomAccessFile(empty, "rw") }
        }
        var done = 0
        for (run in SplitMath.runs(pos, len, totalSize, pieceSize)) {
            val f = if (split) File(target.parentFile, SplitMath.pieceName(target.name, run.piece) + SplitMath.PART) else File(target.parentFile, target.name + SplitMath.PART)
            val raf = writers.getOrPut(f) { RandomAccessFile(f, "rw") }
            raf.seek(run.local)
            raf.write(buf, off + done, run.length)
            done += run.length
        }
    }

    @Synchronized
    override fun flush(path: String) {
        val target = file(path)
        for ((f, raf) in writers) if (f.parentFile == target.parentFile && belongs(f.name, target.name)) raf.fd.sync()
    }

    @Synchronized
    override fun commit(path: String) {
        val target = file(path)
        closeWriters(target)
        val parts = partFiles(target)
        // Last piece first, 001 last: 001 is the commit mark.
        for (p in parts.sortedByDescending { it.name }) {
            val finalName = p.name.removeSuffix(SplitMath.PART)
            if (!p.renameTo(File(p.parentFile, finalName))) throw IOException("could not rename ${p.name}")
        }
    }

    @Synchronized
    override fun rename(from: String, to: String) {
        val src = file(from)
        val dst = file(to)
        closeWriters(src)
        dst.parentFile?.mkdirs()
        if (src.isDirectory) {
            if (dst.exists()) dst.deleteRecursively()
            if (!src.renameTo(dst)) throw IOException("could not move the folder")
            return
        }
        val files = pieces(src) ?: throw IOException("nothing to move")
        // The target's old content (whole or pieces) goes first so a shorter new file doesn't inherit stale pieces.
        delete(to)
        if (files.size == 1 && SplitMath.parsePiece(files[0].name) == null) {
            if (!files[0].renameTo(dst)) throw IOException("could not move the file")
            return
        }
        for ((i, f) in files.withIndex().reversed()) {
            if (!f.renameTo(File(dst.parentFile, SplitMath.pieceName(dst.name, i)))) throw IOException("could not move ${f.name}")
        }
    }

    @Synchronized
    override fun delete(path: String) {
        val target = file(path)
        closeWriters(target)
        if (target.isDirectory) {
            target.deleteRecursively()
            return
        }
        target.parentFile?.listFiles()?.filter { belongs(it.name, target.name) }?.forEach { it.delete() }
    }

    override fun mkdirs(dir: String) {
        file(dir).mkdirs()
    }

    /** `name`, `name.part`, `name.NNN`, `name.NNN.part` of the same logical file. */
    private fun belongs(physical: String, name: String): Boolean {
        val bare = physical.removeSuffix(SplitMath.PART)
        return bare == name || SplitMath.parsePiece(bare)?.first == name
    }

    private fun closeWriters(target: File) {
        val it = writers.entries.iterator()
        while (it.hasNext()) {
            val (f, raf) = it.next()
            if (f.parentFile == target.parentFile && belongs(f.name, target.name) || target.isDirectory && f.path.startsWith(target.path)) {
                runCatching { raf.fd.sync() }
                runCatching { raf.close() }
                it.remove()
            }
        }
    }

    private fun partFiles(target: File): List<File> =
        target.parentFile?.listFiles()?.filter { it.name.endsWith(SplitMath.PART) && belongs(it.name, target.name) }.orEmpty()

    /**
     * The files that make up a committed logical file, in order: the whole file, or pieces 001…n when the set is
     * complete: at least two pieces, no gap, every piece but the last full size, the last non-empty and no bigger
     * than a piece. Null = no such file, or an incomplete set.
     */
    private fun pieces(target: File): List<File>? {
        val dir = target.parentFile ?: return null
        val whole = File(dir, target.name)
        if (whole.isFile) return listOf(whole)
        val first = File(dir, SplitMath.pieceName(target.name, 0))
        if (!first.isFile) return null
        val out = arrayListOf(first)
        while (true) {
            val next = File(dir, SplitMath.pieceName(target.name, out.size))
            if (!next.isFile) break
            out += next
        }
        // One lone piece is not a file (a file that small is stored whole); a higher piece after a gap means one is missing.
        if (out.size < 2) return null
        val strayAbove = dir.list().orEmpty().any { n -> SplitMath.parsePiece(n)?.let { it.first == target.name && it.second >= out.size } == true }
        if (strayAbove) return null
        if (out.dropLast(1).any { it.length() != pieceSize } || out.last().length() == 0L || out.last().length() > pieceSize) return null
        return out
    }

    /** A logical path under [root]; `..` and absolute paths are refused so a bad name can't leave the disk folder. */
    private fun file(path: String): File {
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == ".." || it == "." }) { "bad path" }
        return parts.fold(root) { f, p -> File(f, p) }
    }
}
