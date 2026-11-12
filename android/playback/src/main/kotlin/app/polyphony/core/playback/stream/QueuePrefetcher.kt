package app.polyphony.core.playback.stream

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import app.polyphony.core.playback.queue.MediaItemFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Fetches the next songs of the queue (as many as [PrefetchPlan] says) fully into the stream
 * cache, one at a time, nearest first, so a network drop doesn't stop the music at the next song:
 * the player reads them from the cache like any song it streamed before. A song that's no longer
 * coming up (skipped, the queue changed) stops being fetched; the fetching stops at a quarter of
 * the cache so it never evicts what was just played. Owned by PlaybackService, on its scope.
 */
class QueuePrefetcher(
    private val scope: CoroutineScope,
    private val resolver: StreamResolver,
    private val streamCache: StreamCache,
    private val upstream: DataSource.Factory,
    /** continuo's HLS URLs with this install's token (no cache: the fetching adds its own). */
    private val hlsUpstream: DataSource.Factory? = null,
) {
    /** One queued song: what to resolve; [hls] when it plays as an adaptive stream (#185). */
    data class Song(val serverId: String, val trackId: String, val hls: Hls? = null)

    /** An adaptive song: its stream's base URL (…/v1/stream/{song}/) and the rendition to fetch. */
    data class Hls(val base: String, val kbps: Int)

    private var job: Job? = null
    private var planned: List<Song> = emptyList()

    private val _fetchedHls: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Adaptive songs fetched ahead in full this session: playable offline from the stream cache. */
    val fetchedHls: Set<String> get() = _fetchedHls

    /** Bring the fetching in line with the plan: [songs] to have cached, nearest first. */
    fun update(songs: List<Song>) {
        if (songs == planned && job?.isActive == true) return
        planned = songs
        job?.cancel()
        if (songs.isEmpty()) return
        job = scope.launch(Dispatchers.IO) { fetch(songs) }
    }

    fun stop() = update(emptyList())

    private suspend fun fetch(songs: List<Song>) {
        val cache = streamCache.current() ?: return
        val cap = streamCache.maxBytes() / 4
        var fetched = 0L
        for (song in songs) {
            if (fetched >= cap) {
                Log.i(TAG, "Fetching ahead stops at ${cap / MB} MB, a quarter of the stream cache")
                return
            }
            if (song.hls != null) {
                val hlsUpstream = hlsUpstream ?: continue
                val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(hlsUpstream)
                try {
                    val bytes = fetchHls(factory, song.hls) { fetched += it }
                    Log.i(TAG, "Fetched ${song.trackId} ahead at ${song.hls.kbps} kbit/s (${bytes / KB} KB)")
                    _fetchedHls += song.trackId
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.i(TAG, "Fetching ${song.trackId} ahead stopped: ${e.message}")
                    return
                }
                continue
            }
            val spec = try {
                resolver.resolveAhead(DataSpec(MediaItemFactory.uriFor(song.serverId, song.trackId))) ?: continue
            } catch (e: IOException) {
                Log.w(TAG, "Can't fetch ${song.trackId} ahead: ${e.message}")
                continue
            }
            val key = spec.key ?: continue
            if (streamCache.isComplete(key)) continue
            val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .createDataSource()
            var written = 0L
            val writer = CacheWriter(source, spec, null) { _, cached, newBytes ->
                written = cached
                fetched += newBytes
            }
            val stopOnCancel = coroutineContext.job.invokeOnCompletion { writer.cancel() }
            try {
                writer.cache()
                Log.i(TAG, "Fetched ${song.trackId} ahead (${written / KB} KB)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Offline again, cancelled, or the server failed: the next update tries again.
                Log.i(TAG, "Fetching ${song.trackId} ahead stopped: ${e.message}")
                return
            } finally {
                stopOnCancel.dispose()
            }
        }
    }

    internal companion object {
        private const val TAG = "PolyphonyPrefetch"
        private const val KB = 1024L
        private const val MB = 1024L * 1024L

        /**
         * An adaptive song into the cache under the URLs the player will ask for: the master playlist,
         * the rendition's playlist, its init segment and every segment. Returns the bytes it wrote.
         */
        internal suspend fun fetchHls(factory: CacheDataSource.Factory, hls: Hls, onBytes: (Long) -> Unit): Long {
            val rendition = "${hls.base}${hls.kbps}/"
            readAll(factory, hls.base + "master.m3u8")
            val files = PrefetchPlan.hlsFiles(String(readAll(factory, rendition + "index.m3u8")))
            var written = 0L
            for (name in files) {
                val writer = CacheWriter(factory.createDataSource(), DataSpec(Uri.parse(rendition + name)), null) { _, _, newBytes ->
                    written += newBytes
                    onBytes(newBytes)
                }
                val stopOnCancel = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                } finally {
                    stopOnCancel.dispose()
                }
            }
            return written
        }

        /** A playlist through the cache (stored as it's read). */
        private fun readAll(factory: CacheDataSource.Factory, url: String): ByteArray {
            val source = factory.createDataSource()
            try {
                source.open(DataSpec(Uri.parse(url)))
                return DataSourceUtil.readToEnd(source)
            } finally {
                source.close()
            }
        }
    }
}
