package com.kirtanya.reel.player

/**
 * Every colour the player's View-based controls draw (the player's token file; docs/DESIGN_STYLE.md
 * "Player: overlays sit on video"). Controls sit on any picture, so they are light on dark: white
 * marks and text, black shades, translucent glass. The TV strip switches between [FOREGROUND] and
 * [STRIP_DARK] with the picture (TvControls). Surfaces that take the accent use Tokens.Night.
 * Nothing else in player/ writes a colour literal (the design-rules scan enforces it).
 */
internal object PlayerColors {
    /** Icons, text, seek bar and focus rings on video. */
    const val FOREGROUND = 0xFFFFFFFF.toInt()
    /** The TV strip over bright pictures: near-black, within 5 % of black's contrast, softer on the eye. */
    const val STRIP_DARK = 0xFF0B0B0C.toInt()
    /** Behind the video (letterbox). */
    const val VIDEO_BACKGROUND = 0xFF000000.toInt()

    /** Secondary text and icons on video ("Ends at", panel icons): 80 % white. */
    const val SECONDARY = 0xCCFFFFFF.toInt()

    // Phone: shades only behind the top and bottom rows, fading to clear.
    const val SHADE_TOP = 0x99000000.toInt()
    const val SHADE_BOTTOM = 0xB3000000.toInt()
    const val SHADE_CLEAR = 0x00000000
    /** Text shadow on video (phone), the unlock button's disc. */
    const val SHADOW = 0x66000000
    /** The double-tap skip bubble. */
    const val BUBBLE = 0x59000000

    // Glass chips on video (speed, next episode, HUD).
    const val CHIP_FILL = 0x33FFFFFF
    const val GLASS_STROKE = 0x26FFFFFF

    // Settings panel.
    const val PANEL = 0xEB141418.toInt()
    const val PANEL_HEADER = 0x8CFFFFFF.toInt()
    const val PANEL_NOTE = 0x99FFFFFF.toInt()
    const val PANEL_VALUE = 0xA6FFFFFF.toInt()
    /** A row under focus (TV) or a finger (phone). */
    const val ROW_HIGHLIGHT = 0x29FFFFFF

    // TV strip text glow: dark under dark text, light under white text.
    const val STRIP_GLOW_UNDER_DARK = 0x59FFFFFF
    const val STRIP_GLOW_UNDER_LIGHT = 0x80000000.toInt()

    // Subtitles: white on a 60 % black box, with a black drop shadow.
    const val SUBTITLE_TEXT = 0xFFFFFFFF.toInt()
    const val SUBTITLE_BOX = 0x99000000.toInt()
    const val SUBTITLE_EDGE = 0xFF000000.toInt()

    /** [c] with alpha [a] (0..255). */
    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}
