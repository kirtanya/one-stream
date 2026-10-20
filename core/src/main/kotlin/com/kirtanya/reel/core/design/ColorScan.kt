package com.kirtanya.reel.core.design

/**
 * Rule 4 (tokens only): finds hard-coded colours in Kotlin source. Colours belong in the token
 * files ([isTokenFile]); everywhere else uses token names (docs/DESIGN_STYLE.md §1).
 *
 * Counted as hard-coded: `Color(0x…)` / `Color(1f, …)` / `Color(red = …)`, named colours
 * (`Color.White`, `Color.BLACK`…), `Color.rgb/argb/parseColor/valueOf(…)` and 8-digit `0xAARRGGBB`
 * literals. Not counted: `Color.Transparent` / `Color.TRANSPARENT` / `Color.Unspecified` ("no
 * colour", the same in every theme), bit masks (`c and 0x00FFFFFF`), comments.
 */
object ColorScan {
    data class Hit(val file: String, val line: Int, val code: String, val literal: String) {
        /** Baseline line: no line number, so edits elsewhere in the file don't break the entry. */
        val entry get() = DesignBaseline.line(file, code, literal)
    }

    /** Files whose job is to define colours (paths relative to the scanned source root). */
    val tokenFiles = listOf("com/kirtanya/reel/ui/theme/", "com/kirtanya/reel/player/PlayerColors.kt")

    fun isTokenFile(relativePath: String) = tokenFiles.any { if (it.endsWith("/")) relativePath.startsWith(it) else relativePath == it }

    private val patterns = listOf(
        Regex("""\bColor\(\s*(?:0x[0-9A-Fa-f]+|\d|red\s*=)[^)]*\)"""),
        Regex("""\bColor\.(?:White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b"""),
        Regex("""\bColor\.(?:WHITE|BLACK|RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|GRAY|LTGRAY|DKGRAY)\b"""),
        Regex("""\bColor\.(?:rgb|argb|parseColor|valueOf)\("""),
    )
    private val hex8 = Regex("""\b0x[0-9A-Fa-f]{8}\b""")
    private val maskOperator = Regex("""\b(?:and|or|xor|inv)\s*\(?\s*$""")

    fun scan(file: String, source: String): List<Hit> {
        val out = ArrayList<Hit>()
        stripComments(source).lines().forEachIndexed { i, line ->
            val taken = ArrayList<IntRange>()
            for (p in patterns) for (m in p.findAll(line)) {
                taken += m.range
                out += Hit(file, i + 1, line.trim(), m.value)
            }
            for (m in hex8.findAll(line)) {
                if (taken.any { m.range.first in it }) continue
                if (maskOperator.containsMatchIn(line.substring(0, m.range.first))) continue
                if (Regex("""^\s*\)?\s*(?:and|or|xor)\b""").containsMatchIn(line.substring(m.range.last + 1).removePrefix(".toInt()"))) continue
                out += Hit(file, i + 1, line.trim(), m.value)
            }
        }
        return out
    }

    /** Blanks out // and /* */ comments (string contents kept), so line numbers stay true. */
    internal fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        var block = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                block > 0 -> {
                    if (c == '*' && next == '/') { block--; sb.append("  "); i += 2; continue }
                    if (c == '/' && next == '*') { block++; sb.append("  "); i += 2; continue }
                    sb.append(if (c == '\n') '\n' else ' ')
                }
                inString -> {
                    if (c == '\\') { sb.append(c).append(next); i += 2; continue }
                    if (c == '"') inString = false
                    sb.append(c)
                }
                c == '\'' -> {
                    // A char literal ('"', '\'', '/'): copy it whole so it can't open a string or comment.
                    val end = src.indexOf('\'', i + if (next == '\\') 3 else 2)
                    if (end in i + 2..i + 7) { sb.append(src, i, end + 1); i = end + 1; continue }
                    sb.append(c)
                }
                c == '"' -> { inString = true; sb.append(c) }
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') { sb.append(' '); i++ }
                    continue
                }
                c == '/' && next == '*' -> { block = 1; sb.append("  "); i += 2; continue }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
