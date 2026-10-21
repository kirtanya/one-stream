package com.kirtanya.reel.core.playback

/**
 * How subtitles look in the player (Settings → Subtitles → Style). Colours are ARGB ints so the
 * player can hand them straight to Media3's CaptionStyleCompat; the app draws the preview with the
 * same SubtitleView, so Settings shows exactly what plays.
 */
data class CaptionStyle(
    val size: Size = Size.MEDIUM,
    val text: TextColour = TextColour.WHITE,
    val background: Background = Background.BOX,
    val edge: Edge = Edge.SHADOW,
    val font: Font = Font.DEFAULT,
    val position: Position = Position.NORMAL,
    /** Use Android's own caption settings (Accessibility → Caption preferences) instead of all of the above but size and position. */
    val system: Boolean = false,
) {
    /** Text height as a fraction of the video's height (SubtitleView.setFractionalTextSize). */
    enum class Size(val label: String, val fraction: Float) {
        SMALL("Small", 0.045f), MEDIUM("Medium", 0.0533f), LARGE("Large", 0.065f), HUGE("Extra large", 0.08f),
    }

    enum class TextColour(val label: String, val argb: Int) {
        WHITE("White", 0xFFFFFFFF.toInt()),
        YELLOW("Yellow", 0xFFFFE14D.toInt()),
        CYAN("Cyan", 0xFF7FE6FF.toInt()),
        GREEN("Green", 0xFF9CF09C.toInt()),
        GREY("Soft grey", 0xFFD6D6D6.toInt()),
    }

    /** The box behind each line. */
    enum class Background(val label: String, val argb: Int) {
        NONE("None", 0x00000000),
        LIGHT("Light box", 0x66000000),
        BOX("Box", 0x99000000.toInt()),
        SOLID("Solid", 0xF0000000.toInt()),
    }

    /** Outline or shadow around the letters (CaptionStyleCompat.EDGE_TYPE_*). */
    enum class Edge(val label: String, val media3: Int) {
        NONE("None", 0), OUTLINE("Outline", 1), SHADOW("Shadow", 2), RAISED("Raised", 3), DEPRESSED("Sunken", 4),
    }

    enum class Font(val label: String) { DEFAULT("Default"), BOLD("Bold"), SERIF("Serif"), CONDENSED("Condensed"), MONO("Mono") }

    /** Distance from the bottom of the video (SubtitleView.setBottomPaddingFraction). Higher clears the phone's controls. */
    enum class Position(val label: String, val bottomFraction: Float) { LOW("Low", 0.03f), NORMAL("Normal", 0.08f), HIGH("High", 0.16f) }

    /** Edge colour: black for every edge but a sunken one reads better a shade lighter. */
    val edgeArgb: Int get() = if (edge == Edge.DEPRESSED) 0xFF202020.toInt() else 0xFF000000.toInt()

    /** With no box, letters need an edge to stay readable on bright scenes: never none + none. */
    val readable: CaptionStyle get() = if (background == Background.NONE && edge == Edge.NONE) copy(edge = Edge.SHADOW) else this

    /** Settings line: "Medium · White · Box". */
    val summary: String
        get() = if (system) "${size.label} · Android caption style" else listOf(size.label, text.label, background.label).joinToString(" · ")

    /** Stored as "KEY=VALUE;…": unknown keys / values fall back to defaults, so old or new versions read it fine. */
    fun encode(): String = listOf(
        "size" to size.name, "text" to text.name, "bg" to background.name, "edge" to edge.name,
        "font" to font.name, "pos" to position.name, "system" to system.toString(),
    ).joinToString(";") { "${it.first}=${it.second}" }

    /** One-tap looks. */
    enum class Preset(val label: String, val style: CaptionStyle) {
        CLASSIC("Classic", CaptionStyle()),
        CINEMA("Cinema", CaptionStyle(background = Background.NONE, edge = Edge.OUTLINE)),
        YELLOW("Yellow", CaptionStyle(text = TextColour.YELLOW, background = Background.NONE, edge = Edge.OUTLINE, font = Font.BOLD)),
        BOXED("Boxed", CaptionStyle(background = Background.SOLID, edge = Edge.NONE)),
        ;

        /** The preset [s] matches, ignoring size and position (those stay the user's). */
        companion object {
            fun of(s: CaptionStyle): Preset? = if (s.system) null else entries.firstOrNull {
                it.style.copy(size = s.size, position = s.position) == s
            }
        }
    }

    companion object {
        fun decode(text: String?, legacySize: String? = null): CaptionStyle {
            val map = text.orEmpty().split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = CaptionStyle()
            return CaptionStyle(
                // Before styles existed only the size was stored (PlaybackPrefs.subtitleSize: SMALL / MEDIUM / LARGE).
                size = pick(map["size"] ?: legacySize, d.size),
                text = pick(map["text"], d.text),
                background = pick(map["bg"], d.background),
                edge = pick(map["edge"], d.edge),
                font = pick(map["font"], d.font),
                position = pick(map["pos"], d.position),
                system = map["system"] == "true",
            )
        }

        private inline fun <reified E : Enum<E>> pick(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
