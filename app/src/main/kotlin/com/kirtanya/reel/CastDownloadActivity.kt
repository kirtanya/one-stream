package com.kirtanya.reel

import android.app.Activity
import android.os.Bundle
import com.kirtanya.reel.core.launcher.LauncherLink
import com.kirtanya.reel.engine.EngineService

/**
 * Trampoline for `onestream://cast/download?…` ("Download on TV" from the phone). Invisible
 * (Theme.NoDisplay), its own empty task affinity, no history, not in Recents: it hands the link to
 * EngineService and finishes before drawing, so whatever the TV is showing (even our player) carries on.
 */
class CastDownloadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = LauncherLink.parse(intent?.dataString) as? LauncherLink.Cast
        // Started while visible, so the foreground service may start from here.
        if (link != null && link.request.download) EngineService.castDownload(this, link.request)
        finish()
    }
}
