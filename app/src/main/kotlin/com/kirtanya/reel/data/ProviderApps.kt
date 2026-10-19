package com.kirtanya.reel.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.kirtanya.reel.core.tmdb.Provider

/**
 * Streaming service (from "where to watch") → the app on this device.
 * Phone and TV packages differ; the first one installed wins, then a name match.
 */
class ProviderApps(private val context: Context) {
    private val pm = context.packageManager

    /** The installed package for [provider], or null. */
    fun packageFor(provider: Provider): String? {
        val name = normalize(provider.name.replace(Regex("(?i)(with ads|amazon channel|apple tv channel|standard)"), ""))
        KNOWN.entries.firstOrNull { name.startsWith(it.key) }?.value?.firstOrNull(::isInstalled)?.let { return it }
        val firstWord = normalize(provider.name.substringBefore(' '))
        if (firstWord.length < 3) return null
        return launchablePackages().firstOrNull { (pkg, label) ->
            val l = normalize(label)
            l.contains(firstWord) || (l.length >= 3 && name.contains(l))
        }?.first
    }

    /** Opens the provider's app; falls back to the JustWatch/TMDB page. Returns false if nothing opened. */
    fun open(provider: Provider, fallbackLink: String?): Boolean {
        packageFor(provider)?.let { pkg ->
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (intent != null) return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return fallbackLink?.let { start(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: false
    }

    private fun start(intent: Intent) = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun isInstalled(pkg: String) = try {
        pm.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun launchablePackages(): List<Pair<String, String>> {
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
        )
        return intents.flatMap { pm.queryIntentActivities(it, 0) }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    companion object {
        /** India first. Each list has TV and phone packages. */
        private val KNOWN = linkedMapOf(
            "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
            "amazonprimevideo" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
            "amazonvideo" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
            "amazon" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
            "primevideo" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
            "jiohotstar" to listOf("in.startv.hotstar"),
            "hotstar" to listOf("in.startv.hotstar"),
            "disneyplus" to listOf("in.startv.hotstar", "com.disney.disneyplus"),
            "jiocinema" to listOf("in.startv.hotstar"),
            "hbomax" to listOf("com.wbd.stream", "com.hbo.hbonow"),
            "max" to listOf("com.wbd.stream"),
            "zee5" to listOf("com.graymatrix.did"),
            "sonyliv" to listOf("com.sonyliv"),
            "youtube" to listOf("com.google.android.youtube.tv", "com.google.android.youtube"),
            "googleplaymovies" to listOf("com.google.android.videos"),
            "googletv" to listOf("com.google.android.videos"),
            "appletv" to listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.android.appletv"),
            "mxplayer" to listOf("com.mxtech.videoplayer.television", "com.mxtech.videoplayer.ad"),
            "sunnxt" to listOf("com.suntv.sunnxt"),
            "aha" to listOf("ahaflix.tv", "ahaflix.android"),
            "lionsgateplay" to listOf("com.lionsgateplay.videoapp"),
            "mubi" to listOf("com.mubi"),
            "crunchyroll" to listOf("com.crunchyroll.crunchyroid"),
        )
    }
}
