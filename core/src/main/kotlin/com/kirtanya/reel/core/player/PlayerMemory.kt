package com.kirtanya.reel.core.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Audio / subtitle delay for one title (titleKey: a film, or a whole show so the next episode keeps it).
 * Stored in the shared TitlePrefs since f44f8cc; [PlayerMemory.delays] only holds what older builds saved
 * here until PlayerStore moves it over (once).
 */
@Serializable
data class TitleDelays(
    val audioDelayMs: Int = 0,
    val subtitleDelayMs: Int = 0,
    /** Last change (ms since epoch), for dropping the oldest titles. */
    val updatedAt: Long = 0,
) {
    val isZero: Boolean get() = audioDelayMs == 0 && subtitleDelayMs == 0
}

/** The player's own small memory (one JSON file): per-title delays and per-show learned skips. */
@Serializable
data class PlayerMemory(
    val delays: Map<String, TitleDelays> = emptyMap(),
    val shows: Map<String, ShowSkips> = emptyMap(),
    /** Last change per show in [shows] (ms since epoch), for dropping the oldest. */
    val showsUpdated: Map<String, Long> = emptyMap(),
) {
    fun delays(key: String): TitleDelays = delays[key] ?: TitleDelays()

    /** Changes one title's delays; both back at 0 forgets the title. Keeps the newest [MAX_TITLES]. */
    fun withDelays(key: String, nowMs: Long, change: (TitleDelays) -> TitleDelays): PlayerMemory {
        val next = change(delays(key)).copy(updatedAt = nowMs)
        val all = if (next.isZero) delays - key else delays + (key to next)
        return copy(delays = if (all.size <= MAX_TITLES) all else all.entries.sortedByDescending { it.value.updatedAt }.take(MAX_TITLES).associate { it.key to it.value })
    }

    fun withShow(key: String, nowMs: Long, change: (ShowSkips) -> ShowSkips): PlayerMemory {
        val next = change(shows[key] ?: ShowSkips())
        if (next == (shows[key] ?: ShowSkips())) return this
        val stamps = showsUpdated + (key to nowMs)
        val keep = if (stamps.size <= MAX_SHOWS) stamps.keys else stamps.entries.sortedByDescending { it.value }.take(MAX_SHOWS).map { it.key }.toSet()
        return copy(shows = (shows + (key to next)).filterKeys { it in keep }, showsUpdated = stamps.filterKeys { it in keep })
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_TITLES = 400
        const val MAX_SHOWS = 200
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** A damaged or missing file starts empty rather than failing playback. */
        fun decode(text: String?): PlayerMemory =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: PlayerMemory()
    }
}
