package com.kirtanya.reel.engine

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Which USB volumes are mounted right now, for downloads saved on them (docs/HOME_DISK.md §24.5). Cheap: no folder is
 * touched. Blocking-free `StorageManager` reads, so the engine calls it every few seconds.
 */
object DownloadVolumes {
    /** Test hook (SourcesProbe `usbDownload unplug`): volumes the app pretends are gone. Nothing is unmounted. */
    @Volatile var pretendGone: Set<String> = emptySet()

    /** UUIDs (`37EE-1DEE`) of the mounted removable volumes, minus the ones a test pretends are gone. */
    fun mountedIds(context: Context): Set<String> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptySet()
        val ids = runCatching {
            sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED }.mapNotNull { it.uuid }.toSet()
        }.getOrDefault(emptySet())
        return ids - pretendGone
    }

    /** Free bytes on the volume holding [dir]; 0 when it cannot be read. */
    fun freeBytes(dir: File): Long = runCatching { android.os.StatFs(dir.path).availableBytes }.getOrDefault(0L)
}

/** What the engine asks the app when a stream becomes a download (Keep): the folder it goes to. Null = the internal Downloads folder. */
fun interface DownloadTargets {
    /** [maxFileBytes] is the biggest file the download will hold (null = unknown). Throws [EngineException] with the plain message when nothing fits. */
    fun forKeep(maxFileBytes: Long?): File?
}
