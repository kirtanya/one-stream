package com.kirtanya.reel.core.httpdl

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class HttpState { QUEUED, DOWNLOADING, RETRYING, PAUSED, WAITING_DISK, DONE, FAILED, NOT_READY }

/**
 * One direct (debrid / HTTP) download, as persisted in `files/http_downloads.json` (docs/HOME_DISK.md §25). The bytes live in
 * `<baseDir>/<id>/<fileName>` (a [com.kirtanya.reel.core.homedisk.UsbStore] rooted at [baseDir]); [place] says whether that is the USB disk
 * (pieces of 4095 MiB when big) or the TV's own storage (never split).
 */
@Serializable
data class HttpDownload(
    val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** The release / title name shown on the row. */
    val name: String,
    /** Name of the stored file (already FAT-safe). */
    val fileName: String,
    /** "usb" or "tv" ([com.kirtanya.reel.core.homedisk.DownloadPlace.tag]). */
    val place: String,
    val baseDir: String,
    /** The volume's UUID when [place] is "usb": the row waits while it is unplugged. */
    val volumeId: String? = null,
    /** The size the source listed (null when it did not say). */
    val expectedBytes: Long? = null,
    /** The real size, known from the first response; 0 until then. */
    val totalBytes: Long = 0,
    /** Bytes that are flushed to the disk: a restart resumes here, never beyond. */
    val doneBytes: Long = 0,
    val state: HttpState = HttpState.QUEUED,
    /** The plain line shown when [state] is FAILED / NOT_READY / RETRYING. */
    val error: String? = null,
    /** ETag, else Last-Modified, of the file being fetched (for If-Range). */
    val validator: String? = null,
    /** The source's provider label ("Torrentio AD …"): names the debrid service in the not-ready line. */
    val provider: String = "",
    /** A film / episode (it has an origin): a tiny file can't be one. */
    val isTitle: Boolean = false,
    val createdAt: Long,
    val finishedAt: Long? = null,
) {
    val key: String get() = HttpDownloads.keyOf(id)

    /** Path inside the store. */
    val path: String get() = "$id/$fileName"

    val finished: Boolean get() = state == HttpState.DONE

    val progress: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** The list's codec and the key every other screen sees (`http:<id>`, never an info-hash). */
object HttpDownloads {
    const val KEY_PREFIX = "http:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val codec = ListSerializer(HttpDownload.serializer())

    fun keyOf(id: String) = KEY_PREFIX + id

    fun isKey(key: String) = key.startsWith(KEY_PREFIX)

    fun idOf(key: String) = key.removePrefix(KEY_PREFIX)

    /** A damaged or missing file is an empty list; a state that was mid-flight comes back as [HttpState.QUEUED] (the app resumes it). */
    fun parse(text: String?): List<HttpDownload> =
        text?.let { runCatching { json.decodeFromString(codec, it) }.getOrNull() }.orEmpty()
            .map { if (it.state == HttpState.DOWNLOADING || it.state == HttpState.RETRYING) it.copy(state = HttpState.QUEUED) else it }

    fun encode(list: List<HttpDownload>): String = json.encodeToString(codec, list)

    /** The service a debrid code names, for "Not ready on AllDebrid yet". Null = not a debrid link we know. */
    fun debridName(provider: String): String? {
        val upper = provider.uppercase()
        for ((code, name) in SERVICES) {
            if (Regex("(^|[^A-Z])$code([^A-Z]|$)").containsMatchIn(upper) || upper.contains(name.uppercase().replace("-", "").replace(" ", ""))) return name
        }
        return null
    }

    private val SERVICES = listOf(
        "AD" to "AllDebrid", "RD" to "Real-Debrid", "PM" to "Premiumize", "DL" to "Debrid-Link", "TB" to "TorBox",
        "ED" to "EasyDebrid", "OC" to "Offcloud", "PK" to "put.io", "DB" to "DebriD", "EN" to "Debrider", "PP" to "PikPak",
    )

    /** The line for a link the debrid service turned down for good (its status clip), never "try again later". */
    fun refusedLine(provider: String, kind: com.kirtanya.reel.core.player.ClipKind): String {
        val service = debridName(provider)
        return if (kind == com.kirtanya.reel.core.player.ClipKind.ACCOUNT) {
            "${service ?: "Your debrid service"} turned this link down. Check your subscription and the key in the addon"
        } else {
            "${service ?: "The service"} couldn't get this file. Pick another source"
        }
    }

    /** "Not ready on AllDebrid yet. Try again later or pick another source" */
    fun notReadyLine(provider: String): String =
        "Not ready on ${debridName(provider) ?: "your debrid service"} yet. Try again later or pick another source"
}
