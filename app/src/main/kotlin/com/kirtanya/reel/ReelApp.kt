package com.kirtanya.reel

import android.app.Application
import android.content.Context
import com.kirtanya.reel.engine.TorrentEngine
import com.kirtanya.reel.server.LocalStreamServer

/** Holds the app-wide singletons. No DI framework: the graph is small. */
class ReelApp : Application() {
    val engine: TorrentEngine by lazy { TorrentEngine(this) }
    val streamServer: LocalStreamServer by lazy { LocalStreamServer(engine) }

    companion object {
        fun get(context: Context): ReelApp = context.applicationContext as ReelApp
    }
}
