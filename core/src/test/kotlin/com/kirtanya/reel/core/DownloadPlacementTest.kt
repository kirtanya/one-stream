package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.DownloadPlacement
import com.kirtanya.reel.core.homedisk.MetaStep
import com.kirtanya.reel.core.homedisk.PlaceWhy
import com.kirtanya.reel.core.homedisk.Placement
import com.kirtanya.reel.core.homedisk.PlacementFacts
import com.kirtanya.reel.core.sources.Quality
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.stream.GiB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DownloadPlacementTest {
    private val usb = PlacementFacts(usbReady = true, usbFreeBytes = 400 * GiB, tvFreeBytes = 1_700_000_000L, sizeBytes = 2 * GiB)

    private fun at(place: DownloadPlace, why: PlaceWhy) = Placement.At(place, why)

    @Test fun noDiskMeansInternalAsBefore() {
        assertEquals(at(DownloadPlace.TV, PlaceWhy.NO_USB), DownloadPlacement.place(usb.copy(usbReady = false)))
        // Even a huge file: no refusal when the disk is not in play, the old behaviour is untouched.
        assertEquals(at(DownloadPlace.TV, PlaceWhy.NO_USB), DownloadPlacement.place(usb.copy(usbReady = false, sizeBytes = 30 * GiB)))
    }

    @Test fun smallFileGoesToUsb() = assertEquals(at(DownloadPlace.USB, PlaceWhy.USB), DownloadPlacement.place(usb))

    @Test fun justUnderTheFatLimitGoesToUsbAndExactlyTheLimitDoesNot() {
        assertEquals(at(DownloadPlace.USB, PlaceWhy.USB), DownloadPlacement.place(usb.copy(sizeBytes = 4 * GiB - 1)))
        assertEquals(PlaceWhy.TOO_BIG, (DownloadPlacement.place(usb.copy(sizeBytes = 4 * GiB)) as Placement.Refuse).why)
    }

    @Test fun unknownSizeIsTreatedAsThreeGiB() {
        assertEquals(at(DownloadPlace.USB, PlaceWhy.USB), DownloadPlacement.place(usb.copy(sizeBytes = null)))
        // 2 GB floor + 3 GiB needed.
        val tight = usb.copy(sizeBytes = null, usbFreeBytes = 2_000_000_000L + 3 * GiB - 1, tvFreeBytes = 0)
        assertEquals(PlaceWhy.NO_ROOM, (DownloadPlacement.place(tight) as Placement.Refuse).why)
    }

    @Test fun bigFileGoesToInternalWhenItFitsOtherwiseIsRefusedWithThePlainMessage() {
        val fits = usb.copy(sizeBytes = 4 * GiB, tvFreeBytes = 6 * GiB)
        assertEquals(at(DownloadPlace.TV, PlaceWhy.BIG_FILE), DownloadPlacement.place(fits))
        // 1 GiB floor must stay: 5 GiB free is just enough for 4 GiB, 5 GiB - 1 is not.
        assertEquals(at(DownloadPlace.TV, PlaceWhy.BIG_FILE), DownloadPlacement.place(fits.copy(tvFreeBytes = 5 * GiB)))
        val no = DownloadPlacement.place(fits.copy(tvFreeBytes = 5 * GiB - 1))
        assertEquals(Placement.Refuse(PlaceWhy.TOO_BIG, "Too big for the USB disk (over 4 GB). Pick a smaller source."), no)
    }

    @Test fun fullUsbFallsBackToInternalForANewDownloadOrRefuses() {
        val full = usb.copy(usbFreeBytes = 2_000_000_000L + 2 * GiB - 1, tvFreeBytes = 10 * GiB)
        assertEquals(at(DownloadPlace.TV, PlaceWhy.USB_FULL), DownloadPlacement.place(full))
        assertEquals(Placement.Refuse(PlaceWhy.NO_ROOM, "Not enough room on the USB disk"), DownloadPlacement.place(full.copy(tvFreeBytes = 1 * GiB)))
        assertEquals(at(DownloadPlace.USB, PlaceWhy.USB), DownloadPlacement.place(full.copy(usbFreeBytes = 2_000_000_000L + 2 * GiB)))
    }

    private fun hex(name: String) = name.map { "%x".format(it.code % 16) }.joinToString("").padEnd(40, '0')

    private fun src(h: String, res: Int?, gb: Long?, kind: SourceKind = SourceKind.TORRENT, extra: String? = null, cam: Boolean = false) = Source(
        kind, "r$h", "p", Quality(resolution = res, lowQualityRip = cam), url = if (kind == SourceKind.DIRECT) "https://x/$h" else null,
        infoHash = if (kind == SourceKind.TORRENT) hex(h) else null, sizeBytes = gb?.let { it * GiB }, extra = extra,
    )

    @Test fun aPickUnderTheLimitIsNeverSwapped() {
        val small = src("a", 1080, 3)
        assertSame(small, DownloadPlacement.preferUnderLimit(listOf(small, src("b", 1080, 1)), small))
        val unknown = src("u", 1080, null)
        assertSame(unknown, DownloadPlacement.preferUnderLimit(listOf(unknown, src("b", 1080, 1)), unknown))
    }

    @Test fun bigPickTakesTheBestRankedSmallOneAtTheSameOrOneLowerQuality() {
        val big = src("big", 2160, 12)
        val ranked = listOf(big, src("big2", 2160, 7), src("x", 1440, null), src("a", 1440, 3), src("b", 2160, 3), src("c", 1080, 1))
        // First under-limit entry in rank order whose quality is 2160 or 1440: "a" (the 1440 one of known size).
        assertEquals(hex("a"), DownloadPlacement.preferUnderLimit(ranked, big).infoHash)
        // Two steps down is too far.
        assertSame(big, DownloadPlacement.preferUnderLimit(listOf(big, src("c", 1080, 1)), big))
    }

    @Test fun swapSkipsExtrasCamsServiceLinksAndDirectLinks() {
        val big = src("big", 1080, 9)
        val others = listOf(
            src("t", 1080, 1, extra = "Trailer"), src("cam", 1080, 1, cam = true), src("d", 1080, 1, kind = SourceKind.DIRECT),
            src("e", 1080, 1, kind = SourceKind.EXTERNAL),
        )
        assertSame(big, DownloadPlacement.preferUnderLimit(listOf(big) + others, big))
        assertEquals(hex("ok"), DownloadPlacement.preferUnderLimit(listOf(big) + others + src("ok", 720, 1), big).infoHash)
    }

    @Test fun unknownResolutionOnlyMatchesUnknown() {
        val big = src("big", null, 9)
        assertEquals(hex("n"), DownloadPlacement.preferUnderLimit(listOf(big, src("a", 1080, 1), src("n", null, 1)), big).infoHash)
    }

    @Test fun afterMetadataKeepsMovesOrPauses() {
        val tvOk = 20 * GiB
        assertEquals(MetaStep.Keep, DownloadPlacement.afterMetadata(true, 3 * GiB, 0, 3 * GiB, tvOk))
        assertEquals(MetaStep.Keep, DownloadPlacement.afterMetadata(false, 9 * GiB, 0, 9 * GiB, tvOk))
        assertEquals(MetaStep.MoveToTv, DownloadPlacement.afterMetadata(true, 5 * GiB, 0, 5 * GiB, tvOk))
        // Data already written is never moved silently; and no room on the TV pauses too.
        assertEquals(MetaStep.PauseTooBig, DownloadPlacement.afterMetadata(true, 5 * GiB, 1, 5 * GiB, tvOk))
        assertEquals(MetaStep.PauseTooBig, DownloadPlacement.afterMetadata(true, 5 * GiB, 0, 5 * GiB, 2 * GiB))
    }
}
