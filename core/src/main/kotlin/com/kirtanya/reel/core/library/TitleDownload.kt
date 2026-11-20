package com.kirtanya.reel.core.library

import com.kirtanya.reel.core.sources.ByteSize

/** What the title page needs to know about one download, torrent or direct link alike (the app maps its snapshot to this). */
data class DownloadFacts(
    /** info-hash, or `http:<id>`. */
    val key: String,
    val http: Boolean,
    val phase: DlPhase,
    val progress: Float,
    val wantedBytes: Long,
    val doneBytes: Long,
    val downBps: Long,
    val error: String? = null,
    /** Why it is held or slowed ("Waiting for night (01:00)", "Retrying in 8 s"). */
    val notice: String? = null,
    val notReady: Boolean = false,
    val waitingForDisk: Boolean = false,
    val waitingForNight: Boolean = false,
    val hasMetadata: Boolean = true,
    val stalledForSec: Int = 0,
)

enum class DlPhase { METADATA, CHECKING, DOWNLOADING, PAUSED, FINISHED, ERROR }

/** The one state a title's (or episode's) download is in, as the page shows it. */
enum class DlKind { IDLE, STARTING, DOWNLOADING, READY, WAITING, PAUSED, NOT_READY, FAILED, DONE }

/** What the page offers for a state. The first one a state lists is the main one. */
enum class DlAction { DOWNLOAD, PLAY, PAUSE, RESUME, RETRY, CHANGE_SOURCE, DELETE }

/**
 * The download button of a title page, ready to draw: [label] over [detail], a thin [bar] (null = none), and the [actions] in order.
 * [playLabel] is the title's own Play button while the state allows playing ("Play · downloading 34%").
 * Equal states compare equal, so a screen that is only waiting for the next tick doesn't redraw.
 */
data class DownloadButtonState(
    val kind: DlKind,
    val label: String,
    val detail: String? = null,
    val bar: Float? = null,
    val actions: List<DlAction> = emptyList(),
    val playLabel: String? = null,
) {
    val canPlay: Boolean get() = DlAction.PLAY in actions
    val main: DlAction? get() = actions.firstOrNull()
}

/** The small mark on an episode row. */
enum class RowMark { NONE, STARTING, PROGRESS, READY, PAUSED, WAITING, PROBLEM, DONE }

/**
 * docs/DESIGN_STYLE.md "Download status on the title page". Pure mapping from what the engine / downloader says to what the
 * page shows, so every state is tested without a device.
 */
object TitleDownload {
    /** Bytes that must be on disk before Play is offered while a torrent still downloads (the stream path then fetches its own head and tail). */
    const val TORRENT_READY_BYTES = 24L shl 20

    /** A direct download is fetched front to back: once this much of the head is on disk, playing can start. */
    const val HTTP_READY_BYTES = 16L shl 20

    private const val STALL_SEC = 120

    /**
     * Enough of this download exists to start playing it while it carries on (never for a paused, held, failed or not-ready one).
     * A file smaller than twice the threshold is ready at half, so the fixed head never exceeds the file itself.
     */
    fun readyToPlay(f: DownloadFacts): Boolean {
        if (f.phase != DlPhase.DOWNLOADING || f.notReady || f.error != null || f.waitingForDisk || f.waitingForNight) return false
        if (f.http && f.notice != null) return false
        if (f.wantedBytes <= 0) return false
        val need = minOf(if (f.http) HTTP_READY_BYTES else TORRENT_READY_BYTES, f.wantedBytes / 2)
        return f.hasMetadata && f.doneBytes >= need && f.stalledForSec < STALL_SEC && f.doneBytes < f.wantedBytes
    }

    /** The state to draw. [facts] null = no download for this item; [starting] = the user pressed Download and nothing exists yet. */
    fun state(facts: DownloadFacts?, starting: Boolean = false, startError: String? = null): DownloadButtonState {
        if (facts == null) {
            return when {
                starting -> DownloadButtonState(DlKind.STARTING, "Starting…")
                startError != null -> DownloadButtonState(DlKind.FAILED, "Couldn't start", startError, actions = listOf(DlAction.DOWNLOAD))
                else -> DownloadButtonState(DlKind.IDLE, "Download", actions = listOf(DlAction.DOWNLOAD))
            }
        }
        val pct = percent(facts)
        val size = facts.wantedBytes.takeIf { it > 0 }?.let(ByteSize::format)
        return when {
            facts.notReady -> {
                // "Not ready on AllDebrid yet. Try again later or pick another source" → label and detail.
                val line = facts.error.orEmpty()
                val label = line.substringBefore(". ").ifBlank { "Not ready yet" }
                val detail = line.substringAfter(". ", "Try again later or pick another source")
                DownloadButtonState(DlKind.NOT_READY, label, detail, actions = listOf(DlAction.RETRY, DlAction.CHANGE_SOURCE, DlAction.DELETE))
            }
            facts.phase == DlPhase.FINISHED ->
                DownloadButtonState(DlKind.DONE, "Downloaded", size, actions = listOf(DlAction.PLAY, DlAction.DELETE), playLabel = "Play")
            facts.phase == DlPhase.ERROR || facts.error != null ->
                DownloadButtonState(DlKind.FAILED, "Download failed", facts.error ?: "It stopped. Try again", actions = listOf(DlAction.RETRY, DlAction.CHANGE_SOURCE, DlAction.DELETE))
            facts.phase == DlPhase.PAUSED ->
                DownloadButtonState(DlKind.PAUSED, "Paused at $pct%", size, bar = bar(facts), actions = listOf(DlAction.RESUME, DlAction.DELETE))
            facts.phase == DlPhase.METADATA ->
                DownloadButtonState(DlKind.STARTING, "Starting…", "Finding peers", actions = listOf(DlAction.DELETE))
            facts.phase == DlPhase.CHECKING ->
                DownloadButtonState(DlKind.STARTING, "Starting…", "Checking files", actions = listOf(DlAction.DELETE))
            else -> running(facts, pct, size)
        }
    }

    private fun running(f: DownloadFacts, pct: Int, size: String?): DownloadButtonState {
        val hold = hold(f)
        if (hold != null) return DownloadButtonState(DlKind.WAITING, hold.first, hold.second, bar = bar(f), actions = listOf(DlAction.PAUSE, DlAction.DELETE))
        val speed = if (f.downBps > 0) "${ByteSize.format(f.downBps)}/s" else null
        val eta = if (f.downBps > 0 && f.wantedBytes > f.doneBytes) etaLine((f.wantedBytes - f.doneBytes) / f.downBps) else null
        val moving = listOfNotNull(speed, eta).joinToString(" · ").ifBlank { null }
        if (readyToPlay(f)) {
            return DownloadButtonState(
                DlKind.READY, "Ready to play", listOfNotNull("Downloading $pct%", moving).joinToString(" · "), bar = bar(f),
                actions = listOf(DlAction.PLAY, DlAction.PAUSE, DlAction.DELETE), playLabel = "Play · downloading $pct%",
            )
        }
        return DownloadButtonState(
            DlKind.DOWNLOADING, "Downloading $pct%", moving ?: f.notice ?: size?.let { "of $it" }, bar = bar(f),
            actions = listOf(DlAction.PAUSE, DlAction.DELETE),
        )
    }

    /** (label, reason) when the download is held, else null: the reason is always said. */
    private fun hold(f: DownloadFacts): Pair<String, String?>? = when {
        f.waitingForDisk -> "Waiting for the disk" to (f.notice ?: "The USB disk isn't plugged in")
        f.waitingForNight -> "Waiting for night" to (f.notice ?: "Downloads only run overnight")
        f.http && f.notice != null -> (if (f.notice.startsWith("Waiting")) f.notice else "Retrying") to f.notice.takeIf { !it.startsWith("Waiting") }
        !f.http && f.stalledForSec >= STALL_SEC -> "Waiting for peers" to "No one is sharing this right now"
        else -> null
    }

    private fun percent(f: DownloadFacts) = (f.progress.coerceIn(0f, 1f) * 100).toInt()

    /** Whole percents only, so a bar that has not visibly moved compares equal and does not redraw. */
    private fun bar(f: DownloadFacts) = percent(f) / 100f

    /** "12 min left", "1 h 05 min left", "under a minute left". */
    fun etaLine(seconds: Long): String = when {
        seconds < 60 -> "under a minute left"
        seconds < 3600 -> "${(seconds + 30) / 60} min left"
        seconds < 24 * 3600 -> "${seconds / 3600} h ${"%02d".format((seconds % 3600) / 60)} min left"
        else -> "more than a day left"
    }

    /** One download out of several for the same film / episode: a finished one, else the furthest running one, a failed one last. */
    fun pick(all: List<DownloadFacts>): DownloadFacts? =
        all.maxWithOrNull(
            compareBy<DownloadFacts> { it.phase == DlPhase.FINISHED }
                .thenBy { it.error == null && !it.notReady && it.phase != DlPhase.ERROR }
                .thenBy { it.doneBytes },
        )

    /**
     * Every item of one title that has something to show: [items] = (item key, facts) for each download of the title
     * (several per item are fine: [pick] chooses), [starting] = items whose Download was just pressed, [startErrors] = items whose
     * start failed. An item with none of these is not in the result (the page treats it as idle). Facts always win over a pending
     * start, which is only there for the moment before the download exists.
     */
    fun states(items: List<Pair<String, DownloadFacts>>, starting: Set<String>, startErrors: Map<String, String>): Map<String, DownloadButtonState> {
        val byItem = items.groupBy({ it.first }, { it.second })
        val out = LinkedHashMap<String, DownloadButtonState>()
        for (key in byItem.keys + starting + startErrors.keys) {
            out[key] = state(byItem[key]?.let(::pick), starting = key in starting, startError = startErrors[key])
        }
        return out
    }

    fun mark(kind: DlKind): RowMark = when (kind) {
        DlKind.IDLE -> RowMark.NONE
        DlKind.STARTING -> RowMark.STARTING
        DlKind.DOWNLOADING -> RowMark.PROGRESS
        DlKind.READY -> RowMark.READY
        DlKind.WAITING -> RowMark.WAITING
        DlKind.PAUSED -> RowMark.PAUSED
        DlKind.NOT_READY, DlKind.FAILED -> RowMark.PROBLEM
        DlKind.DONE -> RowMark.DONE
    }

    /** "3 of 10 downloaded" (+ " · 1 downloading"); null when nothing in the season is downloaded or downloading. */
    fun seasonLine(states: Collection<DownloadButtonState?>, total: Int): String? {
        val done = states.count { it?.kind == DlKind.DONE }
        val busy = states.count { it != null && it.kind != DlKind.DONE && it.kind != DlKind.IDLE && it.kind != DlKind.FAILED && it.kind != DlKind.NOT_READY }
        if (done == 0 && busy == 0 || total <= 0) return null
        val head = if (done > 0) "$done of $total downloaded" else null
        val tail = if (busy > 0) "$busy downloading" else null
        return listOfNotNull(head, tail).joinToString(" · ")
    }
}
