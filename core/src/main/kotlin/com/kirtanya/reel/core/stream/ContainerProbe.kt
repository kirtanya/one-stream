package com.kirtanya.reel.core.stream

enum class ContainerKind { MKV, MP4, FMP4, TS, AVI, OTHER }

/**
 * What the player needs before its first frame: the header (up to [headerEnd]) and, for some
 * containers, an index elsewhere in the file ([index]: MP4 `moov` after the data, MKV Cues).
 */
data class ContainerLayout(val kind: ContainerKind, val headerEnd: Long, val index: LongRange?, val needTail: Boolean)

sealed interface ProbeStep {
    /** Read these bytes and call [ContainerProbe.step] again. */
    data class Need(val range: LongRange) : ProbeStep
    data class Done(val layout: ContainerLayout) : ProbeStep
}

/**
 * Finds where a video file keeps its index, from its first bytes, so the engine fetches exactly
 * that instead of a blind "last 4 MiB" (docs/STREAMING_V2.md §4.8). Pure: [step] asks for bytes
 * through `read(pos, len)` (null when not available yet).
 */
class ContainerProbe(val size: Long, val extension: String) {
    private var steps = 0

    fun step(read: (pos: Long, len: Int) -> ByteArray?): ProbeStep {
        steps++
        return when (extension.lowercase()) {
            "mp4", "m4v", "mov" -> mp4(read)
            "mkv", "webm" -> mkv(read)
            "ts", "m2ts" -> ProbeStep.Done(tail(ContainerKind.TS, 1 * MiB))
            "avi" -> ProbeStep.Done(tail(ContainerKind.AVI, 4 * MiB))
            else -> ProbeStep.Done(tail(ContainerKind.OTHER, 4 * MiB))
        }
    }

    private fun tail(kind: ContainerKind, bytes: Long) =
        ContainerLayout(kind, minOf(size, 2 * MiB), maxOf(0, size - bytes) until size, needTail = true)

    private fun fallback() = tail(ContainerKind.OTHER, 4 * MiB)

    // ------------------------------------------------------------------ MP4

    private fun mp4(read: (Long, Int) -> ByteArray?): ProbeStep {
        var pos = 0L
        var moov: LongRange? = null
        var mdat: LongRange? = null
        var fragmented = false
        var boxes = 0
        while (pos + 8 <= size && boxes++ < 64) {
            val head = read(pos, 16) ?: return need(pos, 16)
            var boxSize = u32(head, 0)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            if (boxSize == 1L) {
                if (head.size < 16) return need(pos, 16)
                boxSize = u64(head, 8)
                headerLen = 16
            } else if (boxSize == 0L) {
                boxSize = size - pos
            }
            if (boxSize < headerLen) break
            val range = pos until minOf(size, pos + boxSize)
            when (type) {
                "moov" -> moov = range
                "mdat" -> if (mdat == null) mdat = range
                "moof", "sidx" -> fragmented = true
            }
            if (moov != null && mdat != null) break
            if (fragmented && mdat != null) break
            pos += boxSize
        }
        val m = moov
        val d = mdat
        return ProbeStep.Done(
            when {
                fragmented -> ContainerLayout(ContainerKind.FMP4, minOf(size, (m?.last ?: 0) + 1 + MiB).coerceAtLeast(minOf(size, 2 * MiB)), null, needTail = false)
                m != null && (d == null || m.first < d.first) ->
                    ContainerLayout(ContainerKind.MP4, minOf(size, m.last + 1 + MiB), m, needTail = false)
                d != null -> ContainerLayout(ContainerKind.MP4, minOf(size, d.first + MiB), (d.last + 1) until minOf(size, d.last + 1 + 32 * MiB), needTail = true)
                else -> fallback()
            },
        )
    }

    // ------------------------------------------------------------------ MKV

    private fun mkv(read: (Long, Int) -> ByteArray?): ProbeStep {
        val head = read(0, 4096) ?: return need(0, 4096)
        var p = 0
        // EBML header element.
        val ebmlId = readId(head, p) ?: return ProbeStep.Done(fallback())
        if (ebmlId.first != 0x1A45DFA3L) return ProbeStep.Done(fallback())
        p += ebmlId.second
        val ebmlSize = readVint(head, p) ?: return ProbeStep.Done(fallback())
        p += ebmlSize.second + ebmlSize.first.toInt()
        // Segment.
        val segId = readId(head, p) ?: return ProbeStep.Done(fallback())
        if (segId.first != 0x18538067L) return ProbeStep.Done(fallback())
        p += segId.second
        val segSize = readVint(head, p) ?: return ProbeStep.Done(fallback())
        p += segSize.second
        val segmentStart = p.toLong()
        // Top-level elements inside the Segment until the first Cluster.
        var pos = segmentStart
        var cues: Long? = null
        var buf = head
        var bufStart = 0L
        var elements = 0
        while (pos < size && elements++ < 64) {
            if (pos + 12 > bufStart + buf.size) {
                if (steps > 3) break
                buf = read(pos, 4096) ?: return need(pos, 4096)
                bufStart = pos
            }
            val off = (pos - bufStart).toInt()
            val id = readId(buf, off) ?: break
            val sz = readVint(buf, off + id.second) ?: break
            val dataStart = pos + id.second + sz.second
            when (id.first) {
                0x114D9B74L -> { // SeekHead
                    val need = (dataStart + sz.first) - (bufStart + buf.size)
                    if (need > 0 && sz.first < 64 * KiB) {
                        buf = read(pos, (id.second + sz.second + sz.first).toInt()) ?: return need(pos, (id.second + sz.second + sz.first).toInt())
                        bufStart = pos
                    }
                    cues = seekHeadCues(buf, (dataStart - bufStart).toInt(), sz.first.toInt())?.let { segmentStart + it } ?: cues
                }
                0x1F43B675L -> { // first Cluster: the header ends here
                    val headerEnd = minOf(size, minOf(pos + MiB, 32 * MiB))
                    val c = cues
                    return ProbeStep.Done(
                        if (c != null && c in 0 until size) ContainerLayout(ContainerKind.MKV, headerEnd, c until minOf(size, c + 4 * MiB), needTail = c > pos)
                        else ContainerLayout(ContainerKind.MKV, headerEnd, maxOf(0, size - 4 * MiB) until size, needTail = true),
                    )
                }
                0x1C53BB6BL -> cues = pos // Cues before the clusters: already in the header
            }
            if (sz.first < 0) break
            pos = dataStart + sz.first
        }
        val c = cues
        return ProbeStep.Done(
            if (c != null && c in 0 until size) ContainerLayout(ContainerKind.MKV, minOf(size, 2 * MiB), c until minOf(size, c + 4 * MiB), needTail = true)
            else ContainerLayout(ContainerKind.MKV, minOf(size, 2 * MiB), maxOf(0, size - 4 * MiB) until size, needTail = true),
        )
    }

    /** Inside a SeekHead: the SeekPosition of the Seek whose SeekID is Cues. */
    private fun seekHeadCues(b: ByteArray, start: Int, len: Int): Long? {
        var p = start
        val end = minOf(b.size, start + len)
        while (p < end) {
            val id = readId(b, p) ?: return null
            val sz = readVint(b, p + id.second) ?: return null
            val ds = p + id.second + sz.second
            if (id.first == 0x4DBBL) { // Seek
                var q = ds
                val qe = minOf(end, ds + sz.first.toInt())
                var seekId = 0L
                var seekPos: Long? = null
                while (q < qe) {
                    val cid = readId(b, q) ?: break
                    val cs = readVint(b, q + cid.second) ?: break
                    val cds = q + cid.second + cs.second
                    if (cid.first == 0x53ABL) seekId = uint(b, cds, cs.first.toInt())
                    if (cid.first == 0x53ACL) seekPos = uint(b, cds, cs.first.toInt())
                    q = cds + cs.first.toInt()
                }
                if (seekId == 0x1C53BB6BL && seekPos != null) return seekPos
            }
            p = ds + sz.first.toInt()
        }
        return null
    }

    private fun need(pos: Long, len: Int): ProbeStep =
        if (steps > 3) ProbeStep.Done(fallback()) else ProbeStep.Need(pos until minOf(size, pos + len))

    companion object {
        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

        private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

        private fun uint(b: ByteArray, o: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) { if (o + i >= b.size) return v; v = (v shl 8) or (b[o + i].toLong() and 0xFF) }
            return v
        }

        /** EBML element id (marker bits kept) and its length in bytes. */
        internal fun readId(b: ByteArray, o: Int): Pair<Long, Int>? {
            if (o >= b.size) return null
            val first = b[o].toInt() and 0xFF
            val len = when { first and 0x80 != 0 -> 1; first and 0x40 != 0 -> 2; first and 0x20 != 0 -> 3; first and 0x10 != 0 -> 4; else -> return null }
            if (o + len > b.size) return null
            return uint(b, o, len) to len
        }

        /** EBML variable-size integer (marker bit removed) and its length; unknown size → (-1, len). */
        internal fun readVint(b: ByteArray, o: Int): Pair<Long, Int>? {
            if (o >= b.size) return null
            val first = b[o].toInt() and 0xFF
            if (first == 0) return null
            var len = 1
            var mask = 0x80
            while (first and mask == 0) { len++; mask = mask shr 1 }
            if (o + len > b.size) return null
            var v = (first and (mask - 1)).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            val allOnes = (1L shl (7 * len)) - 1
            return (if (v == allOnes) -1L else v) to len
        }
    }
}

/** What to fetch before the first frame (byte ranges in the file). */
data class StartupSet(val header: LongRange, val index: LongRange?, val resumeGuess: LongRange?)

object StartupPlanner {
    /** Before the probe: the head, a blind tail by extension, and a guess at the resume point. */
    fun initial(span: FileSpan, extension: String, startMs: Long, durationMs: Long?, rate: MediaRate, profile: SchedulerProfile): StartupSet {
        val size = span.size
        val header = 0L until minOf(size, profile.startupHeadBytes)
        val tailBytes = profile.startupTailBytes(extension.lowercase())
        val index = if (size > header.last + 1) maxOf(header.last + 1, size - tailBytes) until size else null
        val resume = if (startMs > 0 && size <= 8 * GiB && durationMs != null && durationMs > 0 && rate.known) {
            val at = size * startMs / durationMs
            val len = if (span.pieceLength > 4 * MiB) 2L * span.pieceLength else minOf(8 * MiB, 2L * span.pieceLength)
            maxOf(0, at - len / 2) until minOf(size, at + len / 2)
        } else null
        return StartupSet(header, index?.takeIf { !it.isEmpty() }, resume)
    }

    /** After the probe: the real header end and index (none for faststart MP4). */
    fun refine(set: StartupSet, layout: ContainerLayout, span: FileSpan): StartupSet {
        val header = 0L until minOf(span.size, maxOf(set.header.last + 1, layout.headerEnd))
        val index = layout.index?.takeIf { layout.needTail && it.first > header.last }
        return set.copy(header = header, index = index)
    }
}
