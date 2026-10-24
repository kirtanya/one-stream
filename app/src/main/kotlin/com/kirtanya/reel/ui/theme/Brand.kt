package com.kirtanya.reel.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Third-party brand colours for rating badges (IMDb, Rotten Tomatoes, Metacritic). A deliberate exception
 * to "tokens only": these marks are recognised by their own colours, so they never follow the title's
 * scheme or light / dark (user-approved design, 2026-10-24). Screens use these names, never the hex values.
 */
object BrandColors {
    /** IMDb: the yellow pill and the black wordmark box. */
    val imdbYellow = Color(0xFFF5C518)
    val imdbBlack = Color(0xFF000000)

    /** Rotten Tomatoes: the Fresh tomato (red body, green leaf) and the Rotten green splat. */
    val tomatoRed = Color(0xFFFA320A)
    val tomatoLeaf = Color(0xFF00912D)
    val splatGreen = Color(0xFF0AC855)

    /** Metacritic score squares: 61+ green, 40–60 yellow, under 40 red. */
    val metaGreen = Color(0xFF66CC33)
    val metaYellow = Color(0xFFFFCC33)
    val metaRed = Color(0xFFFF0000)
    /**
     * The number on the square. Metacritic's own is white, but white on its green is 2.1:1 (fails the
     * contrast rule, 4.5:1); black reads on all three colours (5.3:1 on the red, the lowest).
     */
    val metaText = Color(0xFF000000)
}
