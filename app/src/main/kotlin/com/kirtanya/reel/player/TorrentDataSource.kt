package com.kirtanya.reel.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.kirtanya.reel.core.stream.ReaderKind
import com.kirtanya.reel.core.stream.StreamErrorException
import com.kirtanya.reel.core.stream.StreamGoneException
import com.kirtanya.reel.core.stream.TorrentStreamRef
import com.kirtanya.reel.engine.StreamReader
import com.kirtanya.reel.engine.TorrentEngine

/**
 * The in-app player reads torrents directly from the engine (no HTTP loopback).
 *
 * A read waits for its piece instead of timing out, so a slow piece is a buffering pause, never
 * "This device can't play this file". A seek interrupts the waiting read at once, so the new
 * position starts loading immediately instead of up to 8 s later.
 */
@UnstableApi
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(/* isNetwork = */ false) {
    private var reader: StreamReader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val ref = TorrentStreamRef.parse(dataSpec.uri.toString())
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val r = engine.openReader(ref, dataSpec.position, ReaderKind.IN_APP)
        if (dataSpec.position > r.size) {
            r.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        reader = r
        uri = dataSpec.uri
        pos = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) r.size - pos else minOf(dataSpec.length, r.size - pos)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val engine: TorrentEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }
}

/**
 * Routes `reel-torrent://` to [torrent] and everything else (direct links, subtitles) to [other].
 * Used inside DefaultMediaSourceFactory so side-loaded subtitles still merge.
 */
@UnstableApi
class ReelDataSourceFactory(private val torrent: DataSource.Factory, private val other: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(torrent, other)
}

@UnstableApi
private class RoutingDataSource(private val torrent: DataSource.Factory, private val other: DataSource.Factory) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null
    private var torrentSource: DataSource? = null
    private var otherSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        torrentSource?.addTransferListener(transferListener)
        otherSource?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val isTorrent = dataSpec.uri.scheme == TorrentStreamRef.SCHEME
        val src = if (isTorrent) {
            torrentSource ?: torrent.createDataSource().also { s -> listeners.forEach(s::addTransferListener); torrentSource = s }
        } else {
            otherSource ?: other.createDataSource().also { s -> listeners.forEach(s::addTransferListener); otherSource = s }
        }
        current = src
        return src.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() {
        current?.close()
        current = null
    }
}

/**
 * Default retries (so subtitles and direct links behave as before), except that a stream the
 * engine stopped or failed is fatal at once with the engine's reason instead of being retried.
 */
@UnstableApi
class ReelLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var e: Throwable? = loadErrorInfo.exception
        while (e != null) {
            if (e is StreamGoneException || e is StreamErrorException) return C.TIME_UNSET
            e = e.cause
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
