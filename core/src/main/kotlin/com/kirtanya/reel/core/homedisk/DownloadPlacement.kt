package com.kirtanya.reel.core.homedisk

import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.stream.CachePolicy
import com.kirtanya.reel.core.stream.FormFactor
import com.kirtanya.reel.core.stream.GiB

/** Where a download is saved (docs/HOME_DISK.md §24). */
enum class DownloadPlace(val tag: String) { USB("usb"), TV("tv") }

/** Why a place was chosen or refused; [tag] goes into ReelStats. */
enum class PlaceWhy(val tag: String) {
    USB("usb"),
    NO_USB("no-usb"),
    BIG_FILE("big-file"),
    USB_FULL("usb-full"),
    TOO_BIG("too-big"),
    NO_ROOM("no-room"),
}

sealed interface Placement {
    data class At(val place: DownloadPlace, val why: PlaceWhy) : Placement

    /** Nothing is added. [message] is what an explicit pick shows; an automatic pick stays silent. */
    data class Refuse(val why: PlaceWhy, val message: String) : Placement
}

/** What the placement reads; the app gathers it. */
data class PlacementFacts(
    /** TV, backend USB, disk Ready (docs/HOME_DISK.md §24.1). False = everything stays as before (internal). */
    val usbReady: Boolean,
    val usbFreeBytes: Long,
    val tvFreeBytes: Long,
    /** The chosen file's size; null when the source does not say. */
    val sizeBytes: Long?,
)

/** What to do once a torrent's real file list is known (docs/HOME_DISK.md §24.3, 4). */
sealed interface MetaStep {
    data object Keep : MetaStep
    data object MoveToTv : MetaStep
    data object PauseTooBig : MetaStep
}

/** Pure placement rules for downloads on the TV's FAT32 USB disk (docs/HOME_DISK.md §24). */
object DownloadPlacement {
    /** FAT32 cannot hold a file of this size or more. */
    const val FAT_LIMIT = 4L * GiB

    /** Size assumed for a source that does not say (same as the next-episode download). */
    const val UNKNOWN_SIZE = 3L * GiB

    const val TOO_BIG_MESSAGE = "Too big for the USB disk (over 4 GB). Pick a smaller source."
    const val NO_ROOM_MESSAGE = "Not enough room on the USB disk"
    const val UNPLUGGED = "USB disk not plugged in"

    private val LADDER = listOf(2160, 1440, 1080, 720, 576, 480)

    fun tvFits(tvFreeBytes: Long, size: Long) = tvFreeBytes - CachePolicy.hardFloor(FormFactor.TV) >= size

    /** The save-path decision for one new download. */
    fun place(f: PlacementFacts): Placement {
        if (!f.usbReady) return Placement.At(DownloadPlace.TV, PlaceWhy.NO_USB)
        val known = f.sizeBytes
        if (known != null && known >= FAT_LIMIT) {
            return if (tvFits(f.tvFreeBytes, known)) Placement.At(DownloadPlace.TV, PlaceWhy.BIG_FILE)
            else Placement.Refuse(PlaceWhy.TOO_BIG, TOO_BIG_MESSAGE)
        }
        val size = known ?: UNKNOWN_SIZE
        if (f.usbFreeBytes - VolumeChoice.MIN_FREE >= size) return Placement.At(DownloadPlace.USB, PlaceWhy.USB)
        return if (tvFits(f.tvFreeBytes, size)) Placement.At(DownloadPlace.TV, PlaceWhy.USB_FULL)
        else Placement.Refuse(PlaceWhy.NO_ROOM, NO_ROOM_MESSAGE)
    }

    /**
     * For an automatic pick: when [chosen] is 4 GiB or more, the best-ranked of [ranked] (already in rank order) that is a
     * downloadable torrent of known size under the limit at the same resolution or one step lower; else [chosen].
     */
    fun preferUnderLimit(ranked: List<Source>, chosen: Source): Source {
        val size = chosen.sizeBytes
        if (size == null || size < FAT_LIMIT) return chosen
        val want = chosen.quality.resolution
        val allowed: Set<Int?> = if (want == null) setOf(null) else {
            val i = LADDER.indexOf(want)
            if (i < 0) setOf(want) else setOfNotNull(LADDER[i], LADDER.getOrNull(i + 1))
        }
        return ranked.firstOrNull {
            it.kind == SourceKind.TORRENT && it.magnetUri() != null && !it.isExtra && !it.quality.lowQualityRip &&
                (it.sizeBytes ?: Long.MAX_VALUE) < FAT_LIMIT && it.quality.resolution in allowed
        } ?: chosen
    }

    /**
     * A torrent saved to USB whose file list just arrived. [maxWantedFile] is the largest wanted file, [downloaded] what is
     * already on disk, [wanted] all wanted bytes. Never moves data that was already written.
     */
    fun afterMetadata(onUsb: Boolean, maxWantedFile: Long, downloaded: Long, wanted: Long, tvFreeBytes: Long): MetaStep {
        if (!onUsb || maxWantedFile < FAT_LIMIT) return MetaStep.Keep
        return if (downloaded == 0L && tvFits(tvFreeBytes, wanted)) MetaStep.MoveToTv else MetaStep.PauseTooBig
    }
}
