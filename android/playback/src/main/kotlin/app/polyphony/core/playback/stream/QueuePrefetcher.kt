package app.polyphony.core.playback.stream

import android.util.Log
import androidx.media3.datasource.DataSource
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
) {
    /** One queued song: what to resolve. */
    data class Song(val serverId: String, val trackId: String)

    private var job: Job? = null
    private var planned: List<Song> = emptyList()

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

    private companion object {
        const val TAG = "PolyphonyPrefetch"
        const val KB = 1024L
        const val MB = 1024L * 1024L
    }
}
