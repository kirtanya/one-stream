package com.kirtanya.reel.ui.common

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.AccessibilityDelegateCompat

/**
 * The TV has three accessibility services on (Button Mapper, One Launcher's show-name reader and lock), none of
 * which reads One Stream. Any enabled service makes Compose rebuild and diff its whole semantics tree every frame
 * and send content-change events: 20-50 ms of the TV's main thread per frame in a perfetto trace (2026-11-04,
 * Compose:semantics:checkForSemanticsChanges / boundUpdates). Compose decides "enabled" from its cached list of
 * enabled services, so emptying that list turns the work off for this window only. The services, the system
 * setting and key handling are untouched.
 *
 * Release builds rename Compose's fields, so they are found by type: the view's AccessibilityDelegateCompat, and in
 * it the List holding AccessibilityServiceInfo (filled lazily, hence the retries). If a Compose update changes
 * this, it logs and does nothing (tool/tv_jank.sh would show the lag again).
 */
object ComposeA11y {
    fun stopSemanticsWork(root: View, attempt: Int = 0) {
        val done = runCatching { tryStop(root) }.getOrElse { Log.w(TAG, "failed: $it"); return }
        if (done) Log.i(TAG, "semantics work off")
        else if (attempt < 10) Handler(Looper.getMainLooper()).postDelayed({ stopSemanticsWork(root, attempt + 1) }, 500)
        else Log.w(TAG, "service list not found")
    }

    private fun tryStop(root: View): Boolean {
        val composeView = find(root) ?: return false
        val delegate = fields(composeView.javaClass).map { it.get(composeView) }.firstOrNull { it is AccessibilityDelegateCompat } ?: return false
        val list = fields(delegate.javaClass).firstOrNull { f ->
            List::class.java.isAssignableFrom(f.type) && (f.get(delegate) as? List<*>)?.firstOrNull() is AccessibilityServiceInfo
        } ?: return false
        list.set(delegate, emptyList<AccessibilityServiceInfo>())
        return true
    }

    private fun fields(c: Class<*>): List<java.lang.reflect.Field> =
        generateSequence(c) { it.superclass }.takeWhile { it != View::class.java && it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }.onEach { it.isAccessible = true }.toList()

    private fun find(v: View): View? {
        if (v is ViewRootForTest) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    private const val TAG = "ReelA11y"
}
