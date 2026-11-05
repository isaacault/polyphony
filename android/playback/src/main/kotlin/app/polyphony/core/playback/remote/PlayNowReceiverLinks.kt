package app.polyphony.core.playback.remote

import android.util.Log
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.playback.di.PlaybackScope
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The links speakers play Play now songs from (continuo#98): a Play now song isn't a library song,
 * so there's no Subsonic stream a receiver could fetch; continuo signs a link to its audio that
 * needs no token, on the LAN door. Shared by DLNA and Cast, and kept for a while (a link is good
 * for 12 hours), because Cast's converter can't wait on the network: [warm] fetches the queue's
 * links ahead, and [blocking] is the converter's short fallback for one that wasn't.
 */
@Singleton
class PlayNowReceiverLinks @Inject constructor(
    private val requests: RequestRepository,
    private val serverLookup: ServerLookup,
    @PlaybackScope private val scope: CoroutineScope,
) {
    private val cache = PlayNowLinkCache(Clock.SYSTEM::nowMs)

    /** The link for [streamId] on [serverId]'s server, or null when continuo wouldn't give one. */
    suspend fun get(serverId: String, streamId: String): String? {
        cache.fresh(streamId)?.let { return it }
        if (cache.recentlyFailed(streamId)) return null
        val server = serverLookup.serverFor(serverId) ?: serverLookup.awaitProvider(serverId)?.server ?: return null
        return try {
            withContext(Dispatchers.IO) { requests.playNowReceiverUrl(server, streamId) }.also { cache.put(streamId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no speaker link for Play now $streamId: ${e.message}")
            cache.failed(streamId)
            null
        }
    }

    /** Fetch the links of [streamIds] now, so a receiver's queue has them when it asks. */
    fun warm(serverId: String, streamIds: Collection<String>) {
        val missing = streamIds.filter { cache.fresh(it) == null && !cache.recentlyFailed(it) }
        if (missing.isEmpty()) return
        scope.launch { missing.forEach { get(serverId, it) } }
    }

    /**
     * For Cast's converter, which runs on the main thread and can't suspend: the link if it's
     * warm, else a short wait for it ([BLOCKING_TIMEOUT_MS]); null when it doesn't come.
     */
    fun blocking(serverId: String, streamId: String): String? =
        cache.fresh(streamId) ?: runBlocking(Dispatchers.IO) { withTimeoutOrNull(BLOCKING_TIMEOUT_MS) { get(serverId, streamId) } }
            .also { if (it == null) cache.failed(streamId) }

    private companion object {
        const val TAG = "PolyphonyPlayNowLinks"
        const val BLOCKING_TIMEOUT_MS = 2_000L
    }
}

/**
 * [PlayNowReceiverLinks]' memory: a link counts for [keepMs] (well inside continuo's 12 hours,
 * so a speaker never gets one about to lapse), and a stream continuo just refused isn't asked
 * for again for [retryMs] (each ask from the converter would block the main thread).
 */
internal class PlayNowLinkCache(
    private val now: () -> Long,
    private val keepMs: Long = 6 * 3_600_000L,
    private val retryMs: Long = 30_000L,
) {
    private val links = ConcurrentHashMap<String, Pair<String, Long>>()
    private val failures = ConcurrentHashMap<String, Long>()

    fun fresh(streamId: String): String? =
        links[streamId]?.takeIf { (_, at) -> now() - at < keepMs }?.first

    fun put(streamId: String, url: String) {
        if (links.size >= MAX) links.entries.minByOrNull { it.value.second }?.let { links.remove(it.key) }
        links[streamId] = url to now()
        failures.remove(streamId)
    }

    fun failed(streamId: String) {
        if (failures.size >= MAX) failures.clear()
        failures[streamId] = now()
    }

    fun recentlyFailed(streamId: String): Boolean = failures[streamId]?.let { now() - it < retryMs } == true

    private companion object {
        const val MAX = 256
    }
}
