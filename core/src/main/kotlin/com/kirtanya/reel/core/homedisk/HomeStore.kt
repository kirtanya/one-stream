package com.kirtanya.reel.core.homedisk

/** A file or folder in a listing. [size] is the logical size (pieces added up), 0 for a folder. */
data class StoreEntry(val name: String, val isDir: Boolean, val size: Long = 0)

/**
 * Everything the app does to the home disk, whichever backend is active (docs/HOME_DISK.md §23.9): a USB drive
 * ([UsbStore], plain files, big ones split into pieces) or a router share (SmbStore in the app). Paths are
 * logical, relative to the disk's `OneStream` root, with `/` between components; callers never see pieces.
 *
 * A file is written as a part file and becomes visible only at [commit] (power-cut safety, §23.6).
 */
interface HomeStore {
    fun freeBytes(): Long?
    fun totalBytes(): Long?

    /** A committed file or a folder. */
    fun exists(path: String): Boolean

    /** Logical size of a committed file; null when it isn't there or isn't complete (a missing piece). */
    fun size(path: String): Long?

    /** Committed files and folders in [dir]; part files and incomplete piece sets are not listed. */
    fun list(dir: String): List<StoreEntry>

    /**
     * Up to [len] bytes at logical [pos] into [buf]; fewer at the end of the file; -1 at or past it.
     * [part] reads the file while it is still being written (the caller only reads ranges its map says are written).
     */
    fun read(path: String, pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size, part: Boolean = false): Int

    /** Writes at [pos] into the part file of a file that will be [totalSize] bytes long (that decides whole vs pieces). */
    fun write(path: String, totalSize: Long, pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size)

    /** The durability point: everything written to this part file so far is on the disk when this returns. */
    fun flush(path: String)

    /** Part file → real file, in the same folder. Safe to call again after an interruption. */
    fun commit(path: String)

    /** Moves a committed file (all its pieces) or a folder; the new folder is created; an existing target is replaced. */
    fun rename(from: String, to: String)

    /** A committed file with its pieces and part files, or a folder with everything in it. Missing is fine. */
    fun delete(path: String)

    fun mkdirs(dir: String)
}
