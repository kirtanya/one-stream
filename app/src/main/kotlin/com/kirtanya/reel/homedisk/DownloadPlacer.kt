package com.kirtanya.reel.homedisk

import android.os.StatFs
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.DownloadPlacement
import com.kirtanya.reel.core.homedisk.HomeBackend
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.PlaceWhy
import com.kirtanya.reel.core.homedisk.Placement
import com.kirtanya.reel.core.homedisk.PlacementFacts
import com.kirtanya.reel.core.homedisk.VolumeChoice
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.engine.EngineStats
import java.io.File

/**
 * Where a new download is saved (docs/HOME_DISK.md §24): the one place that reads the disk's and the TV's free space,
 * applies [DownloadPlacement] and writes the `dl-place` line. Blocking (reads volumes): call off the main thread.
 */
class DownloadPlacer(private val app: ReelApp) {
    /** What was decided: [saveDir] null = the internal Downloads folder (the engine's default). */
    class Decision(val saveDir: File?, val placement: Placement) {
        val refused: Placement.Refuse? get() = placement as? Placement.Refuse
    }

    private class Usb(val downloads: File, val freeBytes: Long)

    /** The disk when it is in play: TV, backend USB, disk Ready (§24.1). */
    private fun usb(): Usb? {
        if (!app.isTv || app.home.settings.value.backend != HomeBackend.USB) return null
        val found = app.home.usb.find()
        if (found.state !is HomeDiskState.Connected) return null
        val appDir = found.appDir ?: return null
        val dir = File(appDir, UsbDisk.DOWNLOADS).also { it.mkdirs() }
        if (!dir.isDirectory) return null
        return Usb(dir, runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L))
    }

    /** The disk's free bytes when it is in play, else null (the Downloads screen's USB line). */
    fun usbFreeBytes(): Long? = usb()?.freeBytes

    /** Is the USB disk in play right now (so a 4 GiB file needs the size rule)? */
    fun usbInPlay(): Boolean = usb() != null

    /** The most a source may weigh to be worth ranking first for a download here: the disk's room under the FAT limit, or what the TV itself can take. */
    fun downloadCap(internalCap: Long): Long {
        val u = usb() ?: return internalCap
        return maxOf(internalCap, minOf(DownloadPlacement.FAT_LIMIT - 1, (u.freeBytes - VolumeChoice.MIN_FREE).coerceAtLeast(0)))
    }

    /**
     * The save path for one download of [sizeBytes] ([src]: button | mylist | auto | prefetch | keep | link). Logs `dl-place`.
     * A refusal carries the plain message; whether to show it is the caller's call (an automatic pick stays silent).
     */
    fun decide(src: String, sizeBytes: Long?): Decision {
        val u = usb()
        val tvFree = if (u == null) 0L else runCatching { StatFs(app.engine.downloadsDir().path).availableBytes }.getOrDefault(0L)
        val p = DownloadPlacement.place(PlacementFacts(u != null, u?.freeBytes ?: 0L, tvFree, sizeBytes))
        val (place, why) = when (p) {
            is Placement.At -> p.place.tag to p.why.tag
            is Placement.Refuse -> "none" to p.why.tag
        }
        EngineStats.log(
            "dl-place",
            linkedMapOf("src" to src, "dev" to if (app.isTv) "tv" else "phone", "place" to place, "why" to why, "size" to sizeBytes, "usbFreeMb" to u?.let { it.freeBytes shr 20 }, "tvFreeMb" to if (u != null) tvFree shr 20 else null),
        )
        val dir = (p as? Placement.At)?.takeIf { it.place == DownloadPlace.USB }?.let { u?.downloads }
        return Decision(dir, p)
    }

    /** No side effects: would [decide] refuse this? (Auto-download counts what it really started.) */
    fun wouldRefuse(sizeBytes: Long?): Boolean {
        val u = usb() ?: return false
        val tvFree = runCatching { StatFs(app.engine.downloadsDir().path).availableBytes }.getOrDefault(0L)
        return DownloadPlacement.place(PlacementFacts(true, u.freeBytes, tvFree, sizeBytes)) is Placement.Refuse
    }

    /** An automatic pick that was swapped for a smaller source (§24.3). */
    fun logSwap(src: String, from: Source, to: Source) {
        if (from === to) return
        EngineStats.log("dl-swap", linkedMapOf("src" to src, "from" to "${from.quality.resolution}/${from.sizeBytes?.shr(20)}", "to" to "${to.quality.resolution}/${to.sizeBytes?.shr(20)}"))
    }

    /** "Keep" (a stream becomes a download): the engine asks for its folder (the engine's `targets` hook). */
    fun forKeep(maxFileBytes: Long?): File? {
        val d = decide("keep", maxFileBytes)
        d.refused?.let { throw com.kirtanya.reel.engine.EngineException(it.message) }
        return d.saveDir
    }
}
