package app.polyphony.core.playback.stream

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LRU on-disk cache for streamed audio. One instance per process (SimpleCache requires exclusive
 * access to its folder). Cache keys are "trackId|profileId" so each quality is cached separately.
 */
@Singleton
class StreamCache @Inject constructor(@ApplicationContext private val context: Context) {
    private var cache: SimpleCache? = null
    private var maxBytes: Long = 0L

    val directory: File get() = File(context.cacheDir, "stream-cache")

    @Synchronized
    fun get(maxSizeMb: Int): SimpleCache {
        cache?.let { return it }
        maxBytes = maxSizeMb.coerceAtLeast(64) * 1024L * 1024L
        return SimpleCache(directory, LeastRecentlyUsedCacheEvictor(maxBytes), StandaloneDatabaseProvider(context)).also { cache = it }
    }

    /** The cache once playback has opened it; null before. */
    @Synchronized
    fun current(): SimpleCache? = cache

    /** The limit [get] opened the cache with (0 before). */
    @Synchronized
    fun maxBytes(): Long = maxBytes

    /** A key under which this track is completely cached, at whatever quality; null when none is. */
    @Synchronized
    fun completeKey(trackId: String): String? {
        val c = cache ?: return null
        return c.keys.firstOrNull { it.startsWith("$trackId|") && isComplete(c, it) }
    }

    /** True when every byte of [key] is cached. */
    @Synchronized
    fun isComplete(key: String): Boolean = cache?.let { isComplete(it, key) } ?: false

    private fun isComplete(c: SimpleCache, key: String): Boolean {
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    @Synchronized
    fun sizeBytes(): Long = cache?.cacheSpace ?: directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Synchronized
    fun clear() {
        val c = cache ?: return
        c.keys.toList().forEach { runCatching { c.removeResource(it) } }
    }

    @Synchronized
    fun release() {
        cache?.release()
        cache = null
    }
}
