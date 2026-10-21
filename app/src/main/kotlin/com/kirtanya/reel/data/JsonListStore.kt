package com.kirtanya.reel.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A list kept in files/[name] (AtomicFile, one IO writer, newest snapshot wins) that loads item by
 * item, so one bad entry doesn't cost the rest. Backs the small synced stores (watched, hidden).
 */
class JsonListStore<T>(context: Context, private val name: String, private val serializer: KSerializer<T>) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.filesDir, name))
    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<T>> = _items.asStateFlow()
    private val pending = Channel<List<T>>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { for (snapshot in pending) write(snapshot) }
    }

    /** Applies [change] atomically; returns the new list. */
    fun update(change: (List<T>) -> List<T>): List<T> {
        val next = synchronized(lock) { change(_items.value).also { _items.value = it } }
        pending.trySend(next)
        return next
    }

    private fun write(snapshot: List<T>) {
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = file.startWrite()
            out!!.write(json.encodeToString(ListSerializer(serializer), snapshot).toByteArray())
            file.finishWrite(out)
        }.onFailure {
            out?.let(file::failWrite)
            Log.w(TAG, "$name: save failed", it)
        }
    }

    private fun load(): List<T> {
        if (!file.baseFile.exists()) return emptyList()
        val text = runCatching { file.readFully().decodeToString() }.getOrNull() ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
        if (array == null) {
            Log.w(TAG, "$name unreadable; kept as $name.bad")
            runCatching { file.baseFile.copyTo(File(file.baseFile.parentFile, "$name.bad"), overwrite = true) }
            return emptyList()
        }
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }
    }

    private companion object {
        const val TAG = "ReelStore"
    }
}
