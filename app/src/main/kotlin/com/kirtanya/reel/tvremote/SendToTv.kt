package com.kirtanya.reel.tvremote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.launcher.CastHint
import com.kirtanya.reel.core.launcher.CastRequest
import com.kirtanya.reel.core.playback.Playback
import com.kirtanya.reel.core.sources.SourceKind
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.tvremote.CastOutcome
import com.kirtanya.reel.core.tvremote.WakeThenPlay

/**
 * "Send to TV": wakes the paired TV (always — the wake key never turns a TV off), waits until its
 * screen is on, then opens `onestream://cast/play?…&t=<position>` there. One Stream on the TV finds
 * the same source (hash / file / url hint) and starts at the phone's position.
 */
object SendToTv {
    /** What the phone shows afterwards. */
    data class Result(val ok: Boolean, val message: String)

    /** Only films and episodes known on TMDB can be sent (the TV searches its own sources for them). */
    fun canSend(p: Playback): Boolean = p.tmdbId != null && p.tmdbType != null && !p.markedOnly

    fun link(p: Playback, positionMs: Long): String? {
        val type = when (p.tmdbType) { "movie" -> MediaType.MOVIE; "tv" -> MediaType.TV; else -> return null }
        val id = p.tmdbId ?: return null
        val s = p.source
        val hint = when (s.kind) {
            SourceKind.TORRENT -> CastHint(hash = s.infoHash, file = s.fileIdx)
            // The phone's own local server means nothing to the TV; a real https link does.
            SourceKind.DIRECT -> CastHint(url = s.url?.takeUnless { it.contains("127.0.0.1") || it.contains("localhost") })
            SourceKind.EXTERNAL -> CastHint()
        }
        return CastRequest.link(false, TitleRef(type, id), p.episode?.season, p.episode?.episode, hint, positionMs.takeIf { it > 5_000 })
    }

    /**
     * For buttons: sends in the background and shows the outcome as a toast. [onSent] runs on the main
     * thread once the TV has it (the player pauses / closes itself there).
     */
    fun sendAndTell(app: ReelApp, p: Playback, positionMs: Long, onSent: () -> Unit = {}) {
        android.widget.Toast.makeText(app, "Sending to ${app.tvRemote.paired?.name ?: "TV"}…", android.widget.Toast.LENGTH_SHORT).show()
        app.appScope.launch {
            val r = send(app, p, positionMs)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(app, r.message, android.widget.Toast.LENGTH_LONG).show()
                if (r.ok) onSent()
            }
        }
    }

    suspend fun send(app: ReelApp, p: Playback, positionMs: Long): Result {
        val remote = app.tvRemote
        val tv = remote.paired ?: return Result(false, "Set up the TV remote first: Settings → Phone & TV")
        val link = link(p, positionMs) ?: return Result(false, "This video can't be sent to the TV")
        val outcome = WakeThenPlay.run(remote, link)
        Log.i("ReelRemote", "send to ${tv.name}: $outcome")
        return when (outcome) {
            is CastOutcome.Sent -> Result(true, if (outcome.wokeUp) "Turning on ${tv.name} and playing there" else "Playing on ${tv.name}")
            CastOutcome.Unreachable -> Result(false, "Couldn't reach ${tv.name}. Is it plugged in and on this Wi-Fi?")
            CastOutcome.DidNotWake -> Result(false, "${tv.name} didn't wake up. Turn it on, then send again.")
        }
    }
}
