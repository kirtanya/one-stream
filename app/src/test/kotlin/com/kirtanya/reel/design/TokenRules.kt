package com.kirtanya.reel.design

import com.kirtanya.reel.core.design.ColorScan
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 4, tokens only: no hard-coded colours in the UI and player code outside the token files
 * (ui/theme/, player/PlayerColors.kt). Pure source scan; the baseline holds what was there before.
 */
class TokenRules {
    @Test fun noNewHardCodedColours() {
        val src = File("src/main/kotlin")
        val dirs = listOf("com/kirtanya/reel/ui", "com/kirtanya/reel/player")
        val files = dirs.flatMap { d -> File(src, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() } +
            File(src, "com/kirtanya/reel/MainActivity.kt")
        val hits = files.map { it.relativeTo(src).invariantSeparatorsPath to it }
            .filterNot { ColorScan.isTokenFile(it.first) }
            .flatMap { (path, f) -> ColorScan.scan(path, f.readText()) }
        val new = Findings.record(Rule.TOKENS, "sources", files.size, hits.map { it.entry })
        val left = Findings.baseline(Rule.TOKENS).size
        println("Hard-coded colours: ${hits.size} found, $left baselined, ${new.size} new")
        assertTrue(
            "New hard-coded colours (use a token from ui/theme/Tokens.kt or player/PlayerColors.kt):\n" + new.joinToString("\n") { "  $it" },
            new.isEmpty(),
        )
    }
}
