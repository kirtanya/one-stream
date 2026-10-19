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
import com.kirtanya.reel.ui.phone.PhoneApp
import com.kirtanya.reel.ui.tv.TvApp
import com.kirtanya.reel.ui.theme.ReelTheme
import com.kirtanya.reel.ui.theme.Tokens

class MainActivity : ComponentActivity() {
    private var incomingMagnet by mutableStateOf<String?>(null)

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
                        TvApp(incomingMagnet = incomingMagnet, onIncomingHandled = { incomingMagnet = null })
                    } else {
                        PhoneApp(incomingMagnet = incomingMagnet, onIncomingHandled = { incomingMagnet = null })
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
    }

    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java)
        return uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
