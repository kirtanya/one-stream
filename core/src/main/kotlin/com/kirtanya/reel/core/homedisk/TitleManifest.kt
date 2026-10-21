package com.kirtanya.reel.core.homedisk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One title on the home disk (docs/HOME_DISK.md §3.2), `incoming/<key>/.onestream/manifest.json` or the library copy. */
@Serializable
data class TitleManifest(
    val schema: Int = 1,
    val key: String,
    /** The torrent's name. */
    val name: String,
    val origin: ManifestOrigin? = null,
    val kind: TitleKind = TitleKind.LIBRARY,
    val state: TitleState = TitleState.INCOMING,
    val pieceLength: Long,
    val totalSize: Long,
    val files: List<ManifestFile>,
    val writer: ManifestWriter,
    val verify: String = VERIFY_PIECES,
    /** Phone copies of v2-only torrents: SHA-256 per 8 MiB chunk, hex. */
    val chunkHashes: List<String>? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val lastUsedAt: Long,
) {
    val wantedBytes: Long get() = files.sumOf { it.size }

    companion object {
        const val VERIFY_PIECES = "pieces-v1"
        const val VERIFY_CHUNKS = "chunks-sha256"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(m: TitleManifest): String = json.encodeToString(serializer(), m)

        /** Null for anything unreadable: a damaged manifest means "not on the disk", never a crash. */
        fun decode(text: String?): TitleManifest? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

@Serializable
data class ManifestOrigin(val type: String, val tmdbId: Int, val season: Int? = null, val episode: Int? = null, val label: String)

@Serializable
data class ManifestFile(
    val index: Int,
    /** The file's first byte in the torrent's byte space (FileSpan math). */
    val offset: Long,
    val size: Long,
    /** Inside `incoming/<key>/`. */
    val path: String,
    /** Under the share's `OneStream/`, once complete. */
    val libraryPath: String? = null,
)

@Serializable
data class ManifestWriter(val device: String, val via: String) {
    companion object {
        const val CONVEYOR = "conveyor"
        const val PHONE_COPY = "phone-copy"
    }
}

@Serializable enum class TitleKind { LIBRARY, CACHE }
@Serializable enum class TitleState { INCOMING, COMPLETE }
