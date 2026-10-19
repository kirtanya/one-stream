package com.kirtanya.reel

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kirtanya.reel.core.magnet.Magnet
import com.kirtanya.reel.engine.EngineService
import com.kirtanya.reel.core.tmdb.MediaType
import com.kirtanya.reel.core.tmdb.TitleRef
import com.kirtanya.reel.ui.nav.Dest
import com.kirtanya.reel.ui.phone.PhoneApp
import com.kirtanya.reel.ui.tv.TvApp
import com.kirtanya.reel.ui.theme.ReelTheme
import com.kirtanya.reel.ui.theme.Tokens

class MainActivity : ComponentActivity() {
    private var incomingMagnet by mutableStateOf<String?>(null)
    private var openDest by mutableStateOf<Dest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EngineService.start(this)
        // `adb shell am start -n com.kirtanya.reel/.MainActivity --ez forceTv true` previews the TV UI on a phone.
        val forceTv = intent.getBooleanExtra(EXTRA_FORCE_TV, false)
        val isTv = forceTv || isTelevision()
        if (forceTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (!isTv && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
        setContent {
            ReelTheme {
                Box(Modifier.fillMaxSize().background(Tokens.Colors.bg).safeDrawingPadding()) {
                    if (isTv) {
                        TvApp(incomingMagnet = incomingMagnet, onIncomingHandled = { incomingMagnet = null }, openDest = openDest, onOpenHandled = { openDest = null })
                    } else {
                        PhoneApp(incomingMagnet = incomingMagnet, onIncomingHandled = { incomingMagnet = null }, openDest = openDest, onOpenHandled = { openDest = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** magnet: links from a browser, or magnet text shared from another app. */
    private fun handleIntent(intent: Intent?) {
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
        val link = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(text)?.value ?: return
        if (Magnet.parse(link) != null) incomingMagnet = link
    }

    companion object {
        private const val EXTRA_FORCE_TV = "forceTv"
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

    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java)
        return uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
