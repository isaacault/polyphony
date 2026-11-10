package app.polyphony.core.playback.connection

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource

/**
 * Reports what a stream source reads to the [ConnectionSampler]. [Kind.NETWORK] wraps the HTTP
 * source under the stream cache, so only bytes that really come off the network are timed (a cache
 * hit never reaches it); [Kind.FILES] wraps the player's whole source and counts only downloaded
 * files (`file://`), which never touch the network.
 */
class MeteredDataSource(
    private val upstream: DataSource,
    private val sampler: ConnectionSampler,
    private val kind: Kind,
) : DataSource {

    enum class Kind { NETWORK, FILES }

    private var stream = 0L
    private var counting = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (kind == Kind.FILES) {
            counting = dataSpec.uri.scheme == "file"
            return upstream.open(dataSpec)
        }
        val start = System.nanoTime()
        val length = upstream.open(dataSpec)
        stream = sampler.newStreamId()
        counting = true
        sampler.onNetworkOpened(stream, (System.nanoTime() - start) / 1_000_000L)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!counting) return upstream.read(buffer, offset, length)
        val start = System.nanoTime()
        val read = upstream.read(buffer, offset, length)
        when (kind) {
            Kind.NETWORK -> sampler.onNetworkRead(stream, start, System.nanoTime(), read)
            Kind.FILES -> if (read > 0) sampler.onDownloadRead(read.toLong())
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (counting && kind == Kind.NETWORK) sampler.onNetworkClosed(stream)
            counting = false
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val sampler: ConnectionSampler,
        private val kind: Kind,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = MeteredDataSource(upstream.createDataSource(), sampler, kind)
    }

    /** Counts what the stream cache serves instead of the network. */
    class CacheListener(private val sampler: ConnectionSampler) : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) = sampler.onCacheRead(cachedBytesRead)

        override fun onCacheIgnored(reason: Int) = Unit
    }
}
