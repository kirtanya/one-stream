package com.kirtanya.reel.core.sources

/**
 * "Quick start": which sources will start playing after a short buffer, before the user picks one. Two signals:
 * a live probe of direct links (first bytes over HTTP: how fast and how soon), and for torrents the swarm and size.
 * The best few that pass, in a wanted language (English, or Hindi for a Hindi-language title), are recommended first.
 * No Android types; the app runs the probes ([StartProbe] is what it measured).
 */

/** What the app measured for one direct link: time to the first byte and throughput over the first ~1 MB; [failed] when it errored, timed out or wasn't media. */
data class StartProbe(val ttfbMs: Long? = null, val bytesPerSec: Long? = null, val failed: Boolean = false)

enum class StartSpeed { FAST, OK, SLOW, FAILED, UNKNOWN }

object QuickStart {
    /** How many recommended sources at most. */
    const val RECOMMENDED = 5

    /** Stable key for a source across searches (probe results are keyed by it). */
    fun key(s: Source): String = s.url ?: s.infoHash?.let { "$it:${s.fileIdx ?: -1}" } ?: "${s.provider}|${s.title}"

    /** Assumed need when size or runtime is unknown: 8 Mbit/s, a good 1080p stream. */
    private const val DEFAULT_NEEDED_BPS = 1_000_000L
    private const val GB = 1_000_000_000L

    /** The direct (HTTP) links worth probing: the first [max] of [ranked] that can be fetched (no external / extra). */
    fun probeTargets(ranked: List<Source>, max: Int = 8): List<Source> =
        ranked.asSequence().filter {
            it.kind == SourceKind.DIRECT && !it.isExtra && it.url?.let { u -> u.startsWith("http://", true) || u.startsWith("https://", true) } == true
        }.take(max).toList()

    /** Bytes per second this source needs to play without stalling (size / runtime), or null when unknown. */
    fun neededBytesPerSec(s: Source, runtimeMin: Int?): Long? {
        val size = s.sizeBytes?.takeIf { it > 0 } ?: return null
        val minutes = runtimeMin?.takeIf { it > 0 } ?: return null
        return (size / (minutes * 60L)).takeIf { it > 0 }
    }

    /** How quickly [s] should start: probes for direct links, swarm + size for torrents. */
    fun speed(s: Source, probe: StartProbe?, runtimeMin: Int?): StartSpeed {
        if (s.isExtra) return StartSpeed.UNKNOWN
        return when (s.kind) {
            SourceKind.EXTERNAL -> StartSpeed.UNKNOWN
            SourceKind.DIRECT -> directSpeed(s, probe, runtimeMin)
            SourceKind.TORRENT -> torrentSpeed(s)
        }
    }

    private fun directSpeed(s: Source, probe: StartProbe?, runtimeMin: Int?): StartSpeed {
        val uncached = DebridCache.of(s) == CacheState.UNCACHED
        if (probe == null) return if (uncached) StartSpeed.SLOW else StartSpeed.UNKNOWN
        if (probe.failed) return StartSpeed.FAILED
        val bps = probe.bytesPerSec ?: return if (uncached) StartSpeed.SLOW else StartSpeed.UNKNOWN
        val needed = neededBytesPerSec(s, runtimeMin) ?: DEFAULT_NEEDED_BPS
        val ttfb = probe.ttfbMs ?: 0L
        val measured = when {
            ttfb > 4000 -> StartSpeed.SLOW
            bps >= needed * 2 && ttfb <= 1500 -> StartSpeed.FAST
            bps >= needed * 1.2 -> StartSpeed.OK
            else -> StartSpeed.SLOW
        }
        // An uncached debrid link answers fast with its "downloading" clip: the probe proves nothing about the film.
        return if (uncached) StartSpeed.SLOW else measured
    }

    /**
     * Torrents: seeders decide, size shifts the thresholds (a big file needs more peers to fill the buffer).
     *
     *   seeders        size            speed
     *   null           any             UNKNOWN
     *   0              any             FAILED
     *   1..4           any             SLOW   (rarely start)
     *   5..14          <= 1.5 GB       OK
     *   5..14          bigger/unknown  SLOW
     *   15..24         any             OK
     *   25..49         <= 2.5 GB       FAST
     *   25..49         bigger/unknown  OK
     *   50+            any             FAST
     */
    private fun torrentSpeed(s: Source): StartSpeed {
        val seeds = s.seeders ?: return StartSpeed.UNKNOWN
        val size = s.sizeBytes
        return when {
            seeds <= 0 -> StartSpeed.FAILED
            seeds < 5 -> StartSpeed.SLOW
            seeds >= 50 -> StartSpeed.FAST
            seeds >= 25 -> if (size != null && size <= 2.5 * GB) StartSpeed.FAST else StartSpeed.OK
            seeds >= 15 -> StartSpeed.OK
            else -> if (size != null && size <= 1.5 * GB) StartSpeed.OK else StartSpeed.SLOW
        }
    }

    /**
     * The languages to prefer, best first: English, or Hindi then English when the title's original language is
     * Hindi ([originalLanguage] is TMDB's ISO 639-1 code, e.g. "hi", "en"). Names are SourceFacts.languages() names.
     */
    fun wantedLanguages(originalLanguage: String?): List<String> =
        if (originalLanguage.equals("hi", ignoreCase = true)) listOf("Hindi", "English") else listOf("English", "Hindi")

    /**
     * Up to [RECOMMENDED] sources from [ranked] that should start fast, in a wanted language first, best first.
     * Empty when none qualifies. The rest of the list keeps [ranked]'s order.
     */
    fun recommend(ranked: List<Source>, probes: Map<String, StartProbe>, runtimeMin: Int?, languages: List<String>): List<Source> {
        class Candidate(val source: Source, val speed: StartSpeed, val language: Int, val index: Int)
        return ranked.mapIndexedNotNull { i, s ->
            val speed = speed(s, probes[key(s)], runtimeMin)
            if (speed != StartSpeed.FAST && speed != StartSpeed.OK) return@mapIndexedNotNull null
            val named = SourceFacts.languages(s)
            val wantedIdx = languages.indexOfFirst { it in named }
            val onlyMulti = named.isNotEmpty() && named.all { it == "Multi" || it == "Dual audio" }
            if (named.isNotEmpty() && wantedIdx < 0 && !onlyMulti) return@mapIndexedNotNull null
            // Named wanted languages rank by wish order. Unnamed releases are nearly always English, so they rank as
            // English (ahead of Hindi for an English title, behind it for a Hindi one); Multi / Dual audio come last.
            val asEnglish = languages.indexOf("English").takeIf { it >= 0 } ?: languages.size
            val rank = when {
                wantedIdx >= 0 -> wantedIdx
                named.isEmpty() -> asEnglish
                else -> languages.size
            }
            Candidate(s, speed, rank, i)
        }.sortedWith(compareBy<Candidate>({ it.speed != StartSpeed.FAST }, { it.language }, { it.index }))
            .take(RECOMMENDED).map { it.source }
    }
}
