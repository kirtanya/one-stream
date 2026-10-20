package com.kirtanya.reel.design

import com.kirtanya.reel.core.design.DesignBaseline
import java.io.File

/** The user's design rules (docs/DESIGN_STYLE.md, CLAUDE.md "Design rules"); [file] names the baseline. */
enum class Rule(val file: String, val title: String) {
    TOUCH("touch-areas", "48 dp touch areas (phone)"),
    CLIPPING("clipping", "No clipping at 360 dp (phone)"),
    CONTRAST("contrast", "Text contrast"),
    TOKENS("hardcoded-colors", "Tokens only (no hard-coded colours)"),
    TV_FOCUS("tv-focus", "TV focus always visible"),
}

/**
 * Compares what one screen found with the baseline (app/src/test/resources/design-baseline/<rule>.txt)
 * and leaves a record in app/build/design-rules/<rule>/ for tool/check_design.sh's summary.
 * Paths are relative to the app module (Gradle runs unit tests there).
 */
object Findings {
    private val baselineDir = File("src/test/resources/design-baseline")
    private val outDir = File("build/design-rules")
    private val cache = HashMap<Rule, List<String>>()

    fun baseline(rule: Rule): List<String> = cache.getOrPut(rule) {
        File(baselineDir, "${rule.file}.txt").takeIf { it.isFile }?.let { DesignBaseline.parse(it.readText()) }.orEmpty()
    }

    /**
     * [where] = the screen (or "all sources" for the colour scan); [found] = baseline-format lines
     * whose first field is [where]. Returns the violations the baseline doesn't cover.
     */
    fun record(rule: Rule, where: String, checked: Int, found: List<String>, skipped: List<String> = emptyList()): List<String> {
        val base = if (rule == Rule.TOKENS) baseline(rule) else baseline(rule).filter { DesignBaseline.where(it) == where }
        val diff = DesignBaseline.diff(found, base)
        val file = File(outDir, "${rule.file}/${where.replace('/', '_')}.txt")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("checked $checked")
            skipped.forEach { appendLine("skipped $it") }
            diff.known.forEach { appendLine("known $it") }
            diff.new.forEach { appendLine("new $it") }
        })
        return diff.new.map { "[${rule.title}] $it" }
    }

    fun failureMessage(where: String, new: List<String>) = buildString {
        appendLine("New design-rule violations on $where (fix them; see CLAUDE.md \"Design rules\"):")
        new.forEach { appendLine("  $it") }
    }
}
