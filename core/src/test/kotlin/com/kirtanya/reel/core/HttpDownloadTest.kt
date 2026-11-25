package com.kirtanya.reel.core

import com.kirtanya.reel.core.homedisk.DownloadPlace
import com.kirtanya.reel.core.homedisk.PlaceWhy
import com.kirtanya.reel.core.homedisk.Placement
import com.kirtanya.reel.core.homedisk.PlacementFacts
import com.kirtanya.reel.core.httpdl.FailKind
import com.kirtanya.reel.core.httpdl.GuardVerdict
import com.kirtanya.reel.core.httpdl.HttpDownload
import com.kirtanya.reel.core.httpdl.HttpDownloads
import com.kirtanya.reel.core.httpdl.HttpPlaceholderGuard
import com.kirtanya.reel.core.httpdl.HttpPlacement
import com.kirtanya.reel.core.httpdl.HttpResume
import com.kirtanya.reel.core.httpdl.HttpState
import com.kirtanya.reel.core.httpdl.ResponseFacts
import com.kirtanya.reel.core.httpdl.ResumeStep
import com.kirtanya.reel.core.httpdl.RetryPolicy
import com.kirtanya.reel.core.player.ClipKind
import com.kirtanya.reel.core.stream.GiB
import com.kirtanya.reel.core.stream.MiB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the direct downloader (docs/HOME_DISK.md §25): codec, resume machine, retry policy, placeholder guard, placement. */
class HttpDownloadTest {
    private fun dl(state: HttpState = HttpState.DONE) = HttpDownload(
        id = "ab12cd", url = "https://x.test/f.mkv", headers = mapOf("User-Agent" to "ua"), name = "Film 2020", fileName = "Film.mkv",
        place = "usb", baseDir = "/mnt/usb/http", volumeId = "37EE-1DEE", expectedBytes = 5 * GiB, totalBytes = 5 * GiB, doneBytes = GiB,
        state = state, validator = "\"etag\"", provider = "Torrentio AD", isTitle = true, createdAt = 1L,
    )

    // ---- codec ----

    @Test fun codecRoundTrips() {
        val list = listOf(dl(), dl(HttpState.PAUSED).copy(id = "ee"))
        assertEquals(list, HttpDownloads.parse(HttpDownloads.encode(list)))
    }

    @Test fun damagedOrMissingFileIsEmpty() {
        assertEquals(emptyList(), HttpDownloads.parse(null))
        assertEquals(emptyList(), HttpDownloads.parse("{not json"))
    }

    @Test fun aRowThatWasMovingBeforeTheRestartComesBackQueued() {
        val back = HttpDownloads.parse(HttpDownloads.encode(listOf(dl(HttpState.DOWNLOADING), dl(HttpState.RETRYING).copy(id = "b"), dl(HttpState.PAUSED).copy(id = "c"))))
        assertEquals(listOf(HttpState.QUEUED, HttpState.QUEUED, HttpState.PAUSED), back.map { it.state })
    }

    @Test fun unknownFieldsAreIgnoredSoANewerFileStillLoads() {
        val text = HttpDownloads.encode(listOf(dl())).replace("\"id\"", "\"future\":1,\"id\"")
        assertEquals(1, HttpDownloads.parse(text).size)
    }

    @Test fun keysAreNeverInfoHashes() {
        assertEquals("http:ab12cd", dl().key)
        assertTrue(HttpDownloads.isKey("http:ab12cd"))
        assertEquals("ab12cd", HttpDownloads.idOf("http:ab12cd"))
        assertEquals(false, HttpDownloads.isKey("a".repeat(40)))
        assertEquals("ab12cd/Film.mkv", dl().path)
    }

    @Test fun debridNamesComeFromTheProviderLabel() {
        assertEquals("AllDebrid", HttpDownloads.debridName("Torrentio AD"))
        assertEquals("Real-Debrid", HttpDownloads.debridName("Torrentio RD | 1080p"))
        assertEquals("Premiumize", HttpDownloads.debridName("Comet PM"))
        assertNull(HttpDownloads.debridName("Torrentio"))
        assertNull(HttpDownloads.debridName("Built-in: Internet Archive"))
        assertEquals("Not ready on AllDebrid yet. Try again later or pick another source", HttpDownloads.notReadyLine("Torrentio AD"))
        assertEquals("Not ready on your debrid service yet. Try again later or pick another source", HttpDownloads.notReadyLine("Comet"))
    }

    @Test fun progressIsAFractionOfTheRealSize() {
        assertEquals(0.2f, dl().progress, 0.001f)
        assertEquals(0f, dl().copy(totalBytes = 0).progress)
    }

    // ---- resume machine ----

    private fun facts(code: Int, len: Long? = null, range: String? = null, validator: String? = null, retryAfter: Long? = null) =
        ResponseFacts(code, len, range, validator, retryAfter)

    @Test fun rangeAnsweredWithTheRightPieceContinues() {
        val s = HttpResume.plan(100, 1000, "e1", facts(206, 900, "bytes 100-999/1000", "e1"))
        assertEquals(ResumeStep.Begin(100, 1000, "e1", restarted = false), s)
    }

    @Test fun rangeStartingElsewhereIsAskedAgainFromTheStart() {
        assertIs<ResumeStep.Refetch>(HttpResume.plan(100, 1000, null, facts(206, 800, "bytes 200-999/1000")))
        assertIs<ResumeStep.Refetch>(HttpResume.plan(100, 1000, null, facts(206, 900, null)))
    }

    @Test fun aResumeWhoseSizeChangedStartsOver() {
        // Seen on the TV: a debrid link with no validator went from 5738 to 4761 MiB and was appended to the old bytes.
        assertIs<ResumeStep.Refetch>(HttpResume.plan(519 * MiB, 5738 * MiB, null, facts(206, 4242 * MiB, "bytes ${519 * MiB}-${4761 * MiB - 1}/${4761 * MiB}")))
        assertIs<ResumeStep.Refetch>(HttpResume.plan(100, 1000, null, facts(206, 1100, "bytes 100-1199/*")))
        assertEquals(ResumeStep.Begin(100, 1000, null, false), HttpResume.plan(100, 1000, null, facts(206, 900, "bytes 100-999/1000")))
    }

    @Test fun aChangedValidatorMeansTheFileChanged() {
        assertIs<ResumeStep.Refetch>(HttpResume.plan(100, 1000, "old", facts(206, 900, "bytes 100-999/1000", "new")))
    }

    @Test fun twoHundredToARangedRequestStartsOver() {
        assertEquals(ResumeStep.Begin(0, 1000, "e", restarted = true), HttpResume.plan(100, 1000, "e", facts(200, 1000, validator = "e")))
        assertEquals(ResumeStep.Begin(0, 1000, null, restarted = false), HttpResume.plan(0, 0, null, facts(200, 1000)))
    }

    @Test fun noLengthIsAPlainFailure() {
        assertEquals(ResumeStep.Fail(HttpResume.NO_LENGTH), HttpResume.plan(0, 0, null, facts(200, null)))
        // A range with an unknown total but a body length still gives the total.
        assertEquals(ResumeStep.Begin(100, 1000, null, false), HttpResume.plan(100, 0, null, facts(206, 900, "bytes 100-999/*")))
    }

    @Test fun rangeNotSatisfiableAtTheEndMeansFinished() {
        assertEquals(ResumeStep.AlreadyDone(1000), HttpResume.plan(1000, 1000, null, facts(416, range = "bytes */1000")))
        assertEquals(ResumeStep.AlreadyDone(1000), HttpResume.plan(1000, 1000, null, facts(416)))
        assertIs<ResumeStep.Refetch>(HttpResume.plan(1200, 1000, null, facts(416, range = "bytes */1000")))
    }

    @Test fun busyAnswersRetryAndFinalOnesFail() {
        assertEquals(ResumeStep.Retry(FailKind.HTTP_BUSY, 30, "The server is busy (429)"), HttpResume.plan(0, 0, null, facts(429, retryAfter = 30)))
        assertIs<ResumeStep.Retry>(HttpResume.plan(0, 0, null, facts(503)))
        assertIs<ResumeStep.Fail>(HttpResume.plan(0, 0, null, facts(404)))
        assertIs<ResumeStep.Fail>(HttpResume.plan(0, 0, null, facts(403)))
    }

    // ---- retry policy ----

    @Test fun backoffDoublesAndIsCapped() {
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L), (1..4).map { RetryPolicy.delayMs(it) })
        assertEquals(RetryPolicy.CAP_MS, RetryPolicy.delayMs(30))
    }

    @Test fun retryAfterWinsWhenLongerButIsCapped() {
        assertEquals(60_000L, RetryPolicy.delayMs(1, 60))
        assertEquals(8_000L, RetryPolicy.delayMs(3, 1))
        assertEquals(RetryPolicy.RETRY_AFTER_CAP_MS, RetryPolicy.delayMs(1, 86_000))
    }

    @Test fun triesAreCountedPerKindAndProgressResetsThem() {
        assertEquals(false, RetryPolicy.giveUp(FailKind.HTTP_BUSY, RetryPolicy.MAX_HTTP))
        assertEquals(true, RetryPolicy.giveUp(FailKind.HTTP_BUSY, RetryPolicy.MAX_HTTP + 1))
        assertEquals(false, RetryPolicy.giveUp(FailKind.NETWORK, RetryPolicy.MAX_HTTP + 1))
        assertEquals(true, RetryPolicy.giveUp(FailKind.FATAL, 1))
        assertEquals(1, RetryPolicy.nextAttempt(5, true))
        assertEquals(6, RetryPolicy.nextAttempt(5, false))
    }

    @Test fun statusCodesClassify() {
        listOf(408, 425, 429, 500, 502, 503, 504, 599).forEach { assertEquals(FailKind.HTTP_BUSY, RetryPolicy.classify(it), "$it") }
        listOf(400, 401, 403, 404, 410, 451).forEach { assertEquals(FailKind.FATAL, RetryPolicy.classify(it), "$it") }
        assertEquals(30L, RetryPolicy.parseRetryAfter(" 30 "))
        assertNull(RetryPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(RetryPolicy.parseRetryAfter(null))
    }

    // ---- placeholder guard ----

    private fun guard(url: String = "https://cdn.test/a/b.mkv", type: String? = "video/x-matroska", total: Long? = 4 * GiB, listed: Long? = 4 * GiB, title: Boolean = true, first: ByteArray? = null) =
        HttpPlaceholderGuard.check(url, type, total, listed, title, first)

    @Test fun aRealFilmPasses() {
        assertEquals(GuardVerdict.Ok, guard())
        assertEquals(GuardVerdict.Ok, guard(type = null, first = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
    }

    @Test fun theKnownStubIsNotReady() {
        assertEquals(GuardVerdict.NotReady("stub"), guard(total = HttpPlaceholderGuard.KNOWN_STUB))
        assertEquals(GuardVerdict.NotReady("stub"), guard(total = HttpPlaceholderGuard.KNOWN_STUB, title = false, listed = 1 * GiB))
    }

    @Test fun aRedirectToAStatusClipIsNotReady() {
        assertEquals(GuardVerdict.NotReady("pattern"), guard(url = "https://static.debrid.test/videos/downloading.mp4", total = 900 * MiB))
        assertEquals(GuardVerdict.NotReady("pattern"), guard(url = "https://x.test/static/videos/wait.mp4"))
    }

    @Test fun anAccountOrFailedClipIsRefusedNotNotReady() {
        assertEquals(GuardVerdict.Refused(ClipKind.ACCOUNT), guard(url = "https://torrentio.test/videos/failed_access_v2.mp4", total = HttpPlaceholderGuard.KNOWN_STUB))
        assertEquals(GuardVerdict.Refused(ClipKind.FAILED), guard(url = "https://torrentio.test/videos/failed_infringement_v2.mp4"))
        assertEquals(GuardVerdict.NotReady("pattern"), guard(url = "https://torrentio.test/videos/downloading_v2.mp4"))
    }

    @Test fun refusedLinesNameTheServiceAndNeverSayTryLater() {
        assertEquals("AllDebrid turned this link down. Check your subscription and the key in the addon", HttpDownloads.refusedLine("Torrentio AD", ClipKind.ACCOUNT))
        assertEquals("The service couldn't get this file. Pick another source", HttpDownloads.refusedLine("Some site", ClipKind.FAILED))
    }

    @Test fun aFilmUnderTwentyMegabytesIsNotOneButAnExtraMayBe() {
        assertEquals(GuardVerdict.NotReady("tiny"), guard(total = 421 * 1024L, listed = null))
        assertEquals(GuardVerdict.Ok, guard(total = 5 * MiB, listed = null, title = false))
    }

    @Test fun aFileFarBelowTheListedSizeIsNotReady() {
        assertEquals(GuardVerdict.NotReady("size"), guard(total = 300 * MiB, listed = 4 * GiB, title = false))
        assertEquals(GuardVerdict.Ok, guard(total = 3 * GiB, listed = 4 * GiB, title = false))
        // A source that lists a small file is not compared.
        assertEquals(GuardVerdict.Ok, guard(total = 5 * MiB, listed = 50 * MiB, title = false))
    }

    @Test fun anErrorPageIsNotAVideo() {
        assertEquals(GuardVerdict.NotVideo, guard(type = "text/html; charset=utf-8", total = 900))
        assertEquals(GuardVerdict.NotVideo, guard(type = "application/json", total = 900))
        assertEquals(GuardVerdict.NotVideo, guard(type = "application/octet-stream", first = "  <html>".toByteArray()))
        assertEquals(GuardVerdict.NotVideo, guard(type = null, first = "{\"error\":1}".toByteArray()))
    }

    // ---- placement ----

    private fun at(f: PlacementFacts) = HttpPlacement.place(f)

    @Test fun aSmallFileGoesToUsbWhenItFits() {
        assertEquals(Placement.At(DownloadPlace.USB, PlaceWhy.USB), at(PlacementFacts(true, 100 * GiB, 5 * GiB, 2 * GiB)))
    }

    @Test fun aBigFileIsAllowedOnUsbBecauseItIsSplit() {
        assertEquals(Placement.At(DownloadPlace.USB, PlaceWhy.USB), at(PlacementFacts(true, 100 * GiB, 5 * GiB, 28 * GiB)))
        assertEquals(Placement.At(DownloadPlace.USB, PlaceWhy.USB), at(PlacementFacts(true, 100 * GiB, 0, 4 * GiB)))
    }

    @Test fun theUsbFloorIsKept() {
        val free = 10 * GiB
        val size = free - 2_000_000_000L
        assertEquals(Placement.At(DownloadPlace.USB, PlaceWhy.USB), at(PlacementFacts(true, free, 0, size)))
        assertIs<Placement.Refuse>(at(PlacementFacts(true, free, 0, size + 1)))
    }

    @Test fun aFullUsbFallsBackToTheTvOnlyWhenItFits() {
        assertEquals(Placement.At(DownloadPlace.TV, PlaceWhy.USB_FULL), at(PlacementFacts(true, GiB, 9 * GiB, 2 * GiB)))
        assertIs<Placement.Refuse>(at(PlacementFacts(true, GiB, 2 * GiB, 2 * GiB)))
    }

    @Test fun noUsbMeansTheTvWithItsFloor() {
        assertEquals(Placement.At(DownloadPlace.TV, PlaceWhy.NO_USB), at(PlacementFacts(false, 0, 5 * GiB, 2 * GiB)))
        assertIs<Placement.Refuse>(at(PlacementFacts(false, 0, 3 * GiB - 1, 2 * GiB)))
    }

    @Test fun unknownSizeAssumesThreeGiB() {
        assertIs<Placement.Refuse>(at(PlacementFacts(false, 0, 3 * GiB, null)))
        assertIs<Placement.At>(at(PlacementFacts(false, 0, 5 * GiB, null)))
    }

    @Test fun theRefusalSaysHowBigInPlainUnits() {
        val r = at(PlacementFacts(true, GiB, GiB, 28 * GiB))
        assertEquals("Not enough space for this file (28 GB)", (r as Placement.Refuse).message)
        assertEquals("Not enough space for this file (4.7 GB)", HttpPlacement.noSpace((4.7 * GiB).toLong()))
        assertEquals("Not enough space for this file (850 MB)", HttpPlacement.noSpace(850 * MiB))
        assertEquals("Not enough space for this file", HttpPlacement.noSpace(null))
    }

    @Test fun theRealSizeIsCheckedAgainstTheVolume() {
        assertEquals(true, HttpPlacement.fits(DownloadPlace.USB, 5 * GiB, 8 * GiB))
        assertEquals(false, HttpPlacement.fits(DownloadPlace.USB, 7 * GiB, 8 * GiB))
        assertEquals(false, HttpPlacement.fits(DownloadPlace.TV, 2 * GiB, 3 * GiB - 1))
    }
}
