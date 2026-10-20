package com.kirtanya.reel

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.engine.EngineService
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.core.sync.SourceLinks
import com.kirtanya.reel.ui.common.AddSourceLinkDialog
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.PhoneApp
import com.kirtanya.reel.ui.tv.TvApp
import com.kirtanya.reel.ui.theme.ReelTheme
import com.kirtanya.reel.ui.theme.Tokens

class MainActivity : ComponentActivity() {
    private var incomingMagnet by mutableStateOf<String?>(null)
    private var openDest by mutableStateOf<Dest?>(null)
    /** A tab to switch to (the downloads notification opens Downloads). */
    private var openTab by mutableStateOf<com.kirtanya.reel.ui.nav.Tab?>(null)
    /** An addon / plugin-repo link opened from a website or shared in: asks before adding. */
    private var incomingSourceLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Content drawn edge to edge; the bar icon colour follows light / dark (set in setContent).
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        EngineService.start(this)
        // `adb shell am start -n com.kirtanya.reel/.MainActivity --ez forceTv true` previews the TV UI on a phone.
        val forceTv = intent.getBooleanExtra(EXTRA_FORCE_TV, false)
        val isTv = forceTv || ReelApp.get(this).isTv
        if (forceTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        com.kirtanya.reel.ui.theme.AccentState.init(this, tv = isTv)
        if (!isTv && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Only a fresh launch: not when Android recreates the activity or it's reopened from Recents
        // (the same link would ask again).
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handleIntent(intent)
        setContent {
            // Status / nav bar icons follow light or dark (the bars themselves stay transparent).
            val dark = com.kirtanya.reel.ui.theme.AccentState.isDark(androidx.compose.foundation.isSystemInDarkTheme())
            androidx.compose.runtime.LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ReelTheme {
                Box(Modifier.fillMaxSize().background(Tokens.Colors.canvas)) {
                    if (isTv) {
                        TvApp(incomingMagnet = incomingMagnet, onIncomingHandled = { incomingMagnet = null }, openDest = openDest, onOpenHandled = { openDest = null }, openTab = openTab, onTabHandled = { openTab = null })
                    } else {
                        PhoneApp(incomingMagnet = incomingMagnet, onIncomingHandled = { incomingMagnet = null }, openDest = openDest, onOpenHandled = { openDest = null }, openTab = openTab, onTabHandled = { openTab = null })
                    }
                    incomingSourceLink?.let { link ->
                        androidx.compose.runtime.key(link) { AddSourceLinkDialog(link, isTv = isTv, onDone = { incomingSourceLink = null }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** magnet: links from a browser, addon Install links (stremio://), or text shared from another app. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_DOWNLOADS) {
            openTab = com.kirtanya.reel.ui.nav.Tab.Downloads
            return
        }
        intent?.getIntExtra(EXTRA_OPEN_ID, -1)?.takeIf { it > 0 }?.let { id ->
            val type = if (intent.getStringExtra(EXTRA_OPEN_TYPE) == "tv") MediaType.TV else MediaType.MOVIE
            val season = intent.getIntExtra(EXTRA_OPEN_SEASON, -1).takeIf { it >= 0 }
            val episode = intent.getIntExtra(EXTRA_OPEN_EPISODE, -1).takeIf { it >= 0 }
            openDest = Dest.Sources(TitleRef(type, id), season, episode)
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        if (Magnet.parse(text.trim()) == null) {
            SourceLinks.extract(text)?.let {
                incomingSourceLink = it
                return
            }
            if (intent?.data?.scheme.equals("stremio", ignoreCase = true)) {
                android.widget.Toast.makeText(this, "That stremio:// link isn't an addon One Stream can add", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        }
        val link = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(text)?.value ?: return
        if (Magnet.parse(link) != null) incomingMagnet = link
    }

    companion object {
        private const val ACTION_OPEN_DOWNLOADS = "com.kirtanya.reel.OPEN_DOWNLOADS"
        private const val EXTRA_FORCE_TV = "forceTv"

        /** Opens the app on its Downloads tab (the downloads notification). */
        fun openDownloads(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Brings the app back as it was (e.g. to the player while streaming): the launcher's own intent. */
        fun resume(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        private const val EXTRA_OPEN_TYPE = "openType"
        private const val EXTRA_OPEN_ID = "openId"
        private const val EXTRA_OPEN_SEASON = "openSeason"
        private const val EXTRA_OPEN_EPISODE = "openEpisode"

        /** Opens the Sources screen for a film / episode (used by the player: "Other sources", "Choose a source"). */
        fun openSources(context: android.content.Context, type: String, id: Int, season: Int?, episode: Int?): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_TYPE, type)
                .putExtra(EXTRA_OPEN_ID, id)
                .putExtra(EXTRA_OPEN_SEASON, season ?: -1)
                .putExtra(EXTRA_OPEN_EPISODE, episode ?: -1)
    }

}
