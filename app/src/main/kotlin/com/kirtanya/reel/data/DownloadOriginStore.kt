package com.kirtanya.reel.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.kirtanya.reel.core.library.DownloadOrigin
import com.kirtanya.reel.core.library.DownloadOrigins
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which film / episode each download was started for (files/download_origins.json), so Downloads
 * can offer "Change source". Small and rarely written: writes happen on the caller's thread.
 */
class DownloadOriginStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "download_origins.json"))
    private val lock = Any()
    private val _all = MutableStateFlow(DownloadOrigins.parse(runCatching { file.readFully().decodeToString() }.getOrNull()))
    val all: StateFlow<Map<String, DownloadOrigin>> = _all.asStateFlow()

    fun get(infoHash: String): DownloadOrigin? = _all.value[infoHash.lowercase()]

    fun put(infoHash: String, origin: DownloadOrigin) = update { it + (infoHash.lowercase() to origin) }

    fun remove(infoHash: String) = update { it - infoHash.lowercase() }

    private fun update(change: (Map<String, DownloadOrigin>) -> Map<String, DownloadOrigin>) = synchronized(lock) {
        val next = change(_all.value)
        if (next == _all.value) return@synchronized
        _all.value = next
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = file.startWrite()
            out!!.write(DownloadOrigins.encode(next).toByteArray())
            file.finishWrite(out)
        }.onFailure {
            out?.let(file::failWrite)
            Log.w("ReelOrigins", "save failed", it)
        }
    }
}
