package com.kirtanya.reel.core.design

/**
 * The user's text rules (docs/DESIGN_STYLE.md, CLAUDE.md "Design rules"): contrast levels and which
 * cut-off text counts as broken.
 */
object TextRules {
    /** WCAG "large text" starts at 18 sp; it needs 3:1, everything smaller 4.5:1. */
    const val LARGE_TEXT_SP = 18f
    const val BODY_CONTRAST = 4.5
    const val LARGE_CONTRAST = 3.0

    fun requiredContrast(fontSizeSp: Float) = if (fontSizeSp >= LARGE_TEXT_SP - 0.01f) LARGE_CONTRAST else BODY_CONTRAST

    /**
     * A short label: one line of at most this many characters (button, chip, tab and settings-row
     * labels). Longer text (titles, release names, descriptions) may end in "…" by design
     * (DESIGN_STYLE §4.7: "long names get maxLines + ellipsis").
     */
    const val SHORT_LABEL_CHARS = 24

    fun isShortLabel(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.length <= SHORT_LABEL_CHARS && '\n' !in t
    }

    /**
     * Ellipsis is a violation only on a short label inside something you press (button, chip, tab,
     * settings row): there it means the label didn't fit, never that a long name was shortened.
     */
    fun ellipsisIsViolation(fullText: String, interactive: Boolean) = interactive && isShortLabel(fullText)
}
