package com.kirtanya.reel.core.httpdl

import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.DownloadPlacement
import com.kirtanya.reel.core.homedisk.PlaceWhy
import com.kirtanya.reel.core.homedisk.Placement
import com.kirtanya.reel.core.homedisk.PlacementFacts
import com.kirtanya.reel.core.homedisk.VolumeChoice
import com.kirtanya.reel.core.stream.GiB
import java.util.Locale

/**
 * Where a direct download goes (docs/HOME_DISK.md §25.2). Unlike a torrent (§24.3) our own writer splits, so a file of 4 GiB or more
 * is fine on the USB disk. Pure.
 */
object HttpPlacement {
    fun place(f: PlacementFacts): Placement {
        val size = f.sizeBytes ?: DownloadPlacement.UNKNOWN_SIZE
        if (f.usbReady) {
            if (f.usbFreeBytes - VolumeChoice.MIN_FREE >= size) return Placement.At(DownloadPlace.USB, PlaceWhy.USB)
            return if (DownloadPlacement.tvFits(f.tvFreeBytes, size)) Placement.At(DownloadPlace.TV, PlaceWhy.USB_FULL)
            else Placement.Refuse(PlaceWhy.NO_ROOM, noSpace(f.sizeBytes))
        }
        return if (DownloadPlacement.tvFits(f.tvFreeBytes, size)) Placement.At(DownloadPlace.TV, PlaceWhy.NO_USB)
        else Placement.Refuse(PlaceWhy.NO_ROOM, noSpace(f.sizeBytes))
    }

    /** After the first response: do [remaining] more bytes fit on a volume with [freeBytes], keeping its floor? */
    fun fits(place: DownloadPlace, remaining: Long, freeBytes: Long): Boolean = when (place) {
        DownloadPlace.USB -> freeBytes - VolumeChoice.MIN_FREE >= remaining
        DownloadPlace.TV -> DownloadPlacement.tvFits(freeBytes, remaining)
    }

    /** "Not enough space for this file (28 GB)". */
    fun noSpace(size: Long?): String =
        if (size == null) "Not enough space for this file" else "Not enough space for this file (${plainSize(size)})"

    /** "28 GB", "4.7 GB", "850 MB": whole numbers from 10 GB up. */
    fun plainSize(bytes: Long): String {
        if (bytes < GiB) return "${maxOf(1, bytes / (1024 * 1024))} MB"
        val g = bytes.toDouble() / GiB
        return String.format(Locale.US, if (g >= 10) "%.0f GB" else "%.1f GB", g)
    }
}
