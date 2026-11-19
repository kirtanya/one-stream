package com.kirtanya.reel.homedisk

import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.HomeStore
import com.kirtanya.reel.core.homedisk.UsbStore
import com.kirtanya.reel.core.homedisk.VolumeChoice
import com.kirtanya.reel.core.homedisk.VolumeFacts
import com.kirtanya.reel.core.homedisk.VolumeState
import java.io.File

/**
 * The USB disk plugged into the TV (docs/HOME_DISK.md §23.2, §23.3): finds the volumes through `StorageManager`,
 * lets [VolumeChoice] pick one, and gives its `OneStream` folder inside the app's own folder on that volume
 * (`getExternalFilesDirs`), which needs no permission on Android TV. Blocking calls: run off the main thread.
 */
class UsbDisk(private val app: ReelApp, private val home: Home) {
    private val storage = app.getSystemService(StorageManager::class.java)

    /** Where the disk stands now, and, when it is usable, its folder. */
    class Found(val state: HomeDiskState, val volumeId: String?, val root: File?)

    fun find(): Found {
        val volumes = ArrayList<VolumeFacts>()
        val folders = HashMap<String, File>()
        // Index 0 is the internal storage; a plugged disk adds one folder, and an unplugged one adds a null we skip.
        for (dir in runCatching { app.getExternalFilesDirs(null) }.getOrDefault(emptyArray())) {
            val v = dir?.let { storage.getStorageVolume(it) } ?: continue
            val id = id(v) ?: continue
            folders[id] = dir
            volumes += facts(v, id, dir)
        }
        // Volumes Android lists but we have no folder on (not mounted, can't be read): they only matter for the message.
        for (v in storage.storageVolumes) {
            val id = id(v) ?: continue
            if (id !in folders) volumes += facts(v, id, null)
        }
        val preferred = home.settings.value.usbVolume
        val picked = VolumeChoice.pick(volumes, preferred)
        val root = picked?.let { folders[it.id] }?.let { File(it, ROOT).also(File::mkdirs) }
        return Found(VolumeChoice.state(volumes, preferred), picked?.id, root?.takeIf { it.isDirectory })
    }

    fun store(): HomeStore? = find().root?.let { UsbStore(it) }

    /** [onChange] runs when any volume is mounted, ejected or removed (API 30+; older TVs rely on the other refresh moments). */
    fun watch(onChange: () -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        storage.registerStorageVolumeCallback(app.mainExecutor, object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = onChange()
        })
    }

    private fun id(v: StorageVolume): String? = v.uuid ?: if (v.isPrimary) "primary" else null

    private fun facts(v: StorageVolume, id: String, dir: File?): VolumeFacts {
        val state = when (v.state) {
            Environment.MEDIA_MOUNTED -> VolumeState.MOUNTED
            Environment.MEDIA_UNMOUNTABLE -> VolumeState.UNMOUNTABLE
            else -> VolumeState.OTHER
        }
        return VolumeFacts(
            id, v.getDescription(app) ?: id, v.isRemovable, v.isEmulated, v.isPrimary, state,
            usable = dir != null && state == VolumeState.MOUNTED,
            freeBytes = dir?.usableSpace ?: 0, totalBytes = dir?.totalSpace ?: 0,
        )
    }

    companion object {
        /** The folder inside the app's own folder on the volume; §3.1's tree hangs below it. */
        const val ROOT = "OneStream"
    }
}
