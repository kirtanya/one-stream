package com.kirtanya.reel.captions

import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.kirtanya.reel.core.playback.CaptionStyle

/**
 * Puts a [CaptionStyle] on a Media3 SubtitleView. The player and the Settings preview both call
 * this, so the preview is exactly what plays.
 */
object CaptionLook {
    fun apply(view: SubtitleView, style: CaptionStyle) {
        val s = style.readable
        // Keep our size and position even with Android's style: those are set per app.
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(s.size.fraction)
        view.setBottomPaddingFraction(s.position.bottomFraction)
        view.setStyle(if (s.system) systemStyle(view) else compat(s))
    }

    fun compat(s: CaptionStyle) = CaptionStyleCompat(
        s.text.argb,
        s.background.argb,
        // No window behind whole cues: the box is per line (background).
        0,
        s.edge.media3,
        s.edgeArgb,
        typeface(s.font),
    )

    private fun systemStyle(view: SubtitleView): CaptionStyleCompat {
        val captions = view.context.getSystemService(CaptioningManager::class.java)
        return captions?.userStyle?.let(CaptionStyleCompat::createFromCaptionStyle) ?: compat(CaptionStyle())
    }

    private fun typeface(f: CaptionStyle.Font): Typeface? = when (f) {
        CaptionStyle.Font.DEFAULT -> null
        CaptionStyle.Font.BOLD -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        CaptionStyle.Font.SERIF -> Typeface.SERIF
        CaptionStyle.Font.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        CaptionStyle.Font.MONO -> Typeface.MONOSPACE
    }
}
