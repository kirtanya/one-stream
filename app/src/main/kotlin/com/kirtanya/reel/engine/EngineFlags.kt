package com.kirtanya.reel.engine

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Switches for the streaming rework (docs/STREAMING_V2.md §11.2), read once at engine start from
 * files/engine_flags.properties. Lets a new behaviour be turned off on a device without a rebuild.
 */
object EngineFlags {
    @Volatile var verboseStats = false; private set
    @Volatile var inapp = true; private set
    @Volatile var zone = true; private set
    @Volatile var grace = true; private set
    @Volatile var govern = true; private set

    private const val FILE = "engine_flags.properties"

    fun load(context: Context) {
        val p = runCatching { Properties().apply { File(context.filesDir, FILE).inputStream().use(::load) } }.getOrNull() ?: return
        fun flag(name: String, default: Boolean) = p.getProperty(name)?.trim()?.let { it == "1" || it.equals("true", true) } ?: default
        verboseStats = flag("verboseStats", false)
        inapp = flag("inapp", true)
        zone = flag("zone", true)
        grace = flag("grace", true)
        govern = flag("govern", true)
    }

    /** "k=v,k=v" from the bench broadcast; merged into the file, applied on next engine start. */
    fun write(context: Context, values: Map<String, String>) {
        val file = File(context.filesDir, FILE)
        val p = Properties().apply { runCatching { file.inputStream().use(::load) } }
        values.forEach { (k, v) -> p.setProperty(k, v) }
        file.outputStream().use { p.store(it, "One Stream engine flags") }
    }

    fun summary(): String = listOf("inapp" to inapp, "zone" to zone, "grace" to grace, "govern" to govern)
        .joinToString(",") { (k, v) -> "$k${if (v) 1 else 0}" }
}
