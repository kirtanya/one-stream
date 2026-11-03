package com.kirtanya.reel.ui.common

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Switches for measuring what the TV's GPU spends its frame on (tool/tv_jank.sh), read once at app start
 * from files/ui_flags.properties and written by the probe (`--es uiFlags "hero=0,img=0"`). Not a user setting.
 */
object UiFlags {
    private var p = Properties()
    private const val FILE = "ui_flags.properties"

    fun load(context: Context) {
        p = runCatching { Properties().apply { File(context.filesDir, FILE).inputStream().use(::load) } }.getOrNull() ?: Properties()
    }

    fun on(name: String, default: Boolean = true): Boolean = p.getProperty(name)?.trim()?.let { it == "1" || it.equals("true", true) } ?: default

    fun str(name: String, default: String): String = p.getProperty(name)?.trim() ?: default

    fun write(context: Context, values: String) {
        val file = File(context.filesDir, FILE)
        val q = Properties()
        if (values != "clear") {
            runCatching { file.inputStream().use(q::load) }
            values.split(',').map { it.split('=') }.filter { it.size == 2 }.forEach { (k, v) -> q.setProperty(k.trim(), v.trim()) }
        }
        file.outputStream().use { q.store(it, "One Stream UI flags") }
        p = q
    }

    fun summary(): String = p.entries.joinToString(",") { "${it.key}=${it.value}" }
}
