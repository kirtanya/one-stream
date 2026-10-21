package com.kirtanya.reel.data

import android.content.Context
import com.kirtanya.reel.core.library.SourceMark
import com.kirtanya.reel.core.library.SourceMarks
import com.kirtanya.reel.core.sources.Source
import com.kirtanya.reel.core.sources.SourceRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Favourite and starred sources (the Sources page's long-press menu), in prefs "source_marks". */
class SourceMarksStore(context: Context) {
    private val prefs = context.getSharedPreferences("source_marks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SourceMark.serializer())

    private val _marks = MutableStateFlow(load())
    val marks: StateFlow<List<SourceMark>> = _marks.asStateFlow()

    fun setFavourite(req: SourceRequest, s: Source, on: Boolean) = update { SourceMarks.set(it, req, s, favourite = on, now = now()) }

    fun setStarred(req: SourceRequest, s: Source, on: Boolean) = update { SourceMarks.set(it, req, s, starred = on, now = now()) }

    @Synchronized
    private fun update(change: (List<SourceMark>) -> List<SourceMark>) {
        val next = change(_marks.value)
        _marks.value = next
        prefs.edit().putString("marks", json.encodeToString(serializer, next)).apply()
    }

    private fun load(): List<SourceMark> =
        prefs.getString("marks", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun now() = System.currentTimeMillis()
}
