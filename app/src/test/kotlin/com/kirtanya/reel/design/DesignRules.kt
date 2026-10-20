package com.kirtanya.reel.design

import com.kirtanya.reel.ScreenRenders
import org.junit.Assert.assertTrue
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * The user's design rules on every screen of [ScreenRenders] (same screens, states and fake data;
 * nothing is saved as a PNG). Run: `bash tool/check_design.sh` (summary) or
 *   ./gradlew :app:testDebugUnitTest --tests 'com.kirtanya.reel.design.*'
 *
 * Phone (360 × 800 dp): touch areas (dark, 100 %), clipping (dark, 100 % and 130 %), contrast
 * (dark and light, 100 %). TV (960 × 540 dp, dark): contrast and focus visibility.
 * A violation fails only when it isn't in app/src/test/resources/design-baseline/<rule>.txt.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenRenders.PHONE)
class DesignRules(variant: Variant) : ScreenRenders(variant) {
    override val rendersBrand get() = false

    override fun onScreenReady(name: String, tv: Boolean, windows: Boolean) {
        val where = if (tv) "tv/$name" else "phone/$name-$variant"
        val checks = ComposeChecks(compose, where)
        val shot = checks.shot()
        val new = ArrayList<String>()
        fun run(rule: Rule, result: ComposeChecks.Result) { new += Findings.record(rule, where, result.checked, result.found, result.skipped) }
        if (!tv && variant == DEFAULT) run(Rule.TOUCH, checks.touch(shot))
        if (!tv && variant.dark) run(Rule.CLIPPING, checks.clipping(shot))
        if (tv || variant.fontScale == 1f) run(Rule.CONTRAST, checks.contrast(shot))
        if (tv) run(Rule.TV_FOCUS, checks.tvFocus(focusManager!!))
        assertTrue(Findings.failureMessage(where, new), new.isEmpty())
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(DEFAULT, Variant(dark = false, fontScale = 1f), Variant(dark = true, fontScale = 1.3f)).map { arrayOf(it) }
    }
}
