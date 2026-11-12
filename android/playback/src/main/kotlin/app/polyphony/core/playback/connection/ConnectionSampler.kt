package app.polyphony.core.playback.connection

import android.util.Log
import app.polyphony.core.domain.connection.ConnectionSample
import app.polyphony.core.domain.connection.ConnectionSummary
import app.polyphony.core.domain.connection.ConnectionWindow
import app.polyphony.core.domain.connection.NetworkContext
import app.polyphony.core.domain.connection.StreamContext
import app.polyphony.core.domain.repository.ConnectionSampleRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.playback.di.PlaybackScope
import app.polyphony.core.playback.engine.HomeNetworkDetector
import app.polyphony.core.playback.engine.LatencyMonitor
import app.polyphony.core.playback.stream.StreamResolver
import app.polyphony.core.playback.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures what the connection is like while this phone streams (#185 phase 1): a
 * [ConnectionSample] about every [WINDOW_MS] of playback, and at once when the network changes or
 * playback stops, stored through [ConnectionSampleRepository]. Fed by the playback service (whether
 * it is streaming, and stalls) and by the stream data sources ([MeteredDataSource], the stream
 * cache's listener). Kept on the phone, and sent to the continuo streamed from ([ConnectionUploader]).
 */
@Singleton
class ConnectionSampler @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val homeNetworkDetector: HomeNetworkDetector,
    latencyMonitor: LatencyMonitor,
    private val streamResolver: StreamResolver,
    private val repository: ConnectionSampleRepository,
    private val uploader: ConnectionUploader,
    private val clock: Clock,
    @PlaybackScope private val scope: CoroutineScope,
) {
    /** The song being played: server id and track id. */
    data class Playing(val serverId: String, val trackId: String)

    private val lock = Any()
    private val window = ConnectionWindow()
    private val streamIds = AtomicLong()
    private var playing: Playing? = null

    init {
        scope.launch {
            latencyMonitor.degraded.collect { capped -> synchronized(lock) { window.onCapped(capped, clock.nowMs()) } }
        }
        scope.launch {
            networkMonitor.state.map { Triple(it.kind, it.isMetered, it.isVpn) }.distinctUntilChanged().drop(1).collect {
                roll(ConnectionSample.Trigger.NETWORK_CHANGE)
            }
        }
        scope.launch {
            homeNetworkDetector.atHome.collect { synchronized(lock) { window.updateNetwork(network()) } }
        }
    }

    /**
     * Called by the playback service about every second: whether this phone's own player is
     * streaming (playing or rebuffering, not paused, not on a Cast or DLNA receiver), and what.
     */
    fun tick(streaming: Boolean, playing: Playing?) {
        val sample = synchronized(lock) {
            // The last song known stays the one a closing window reports.
            if (playing != null) this.playing = playing
            val now = clock.nowMs()
            val started = window.startedAtMs
            when {
                streaming && started == null -> {
                    window.open(now, network())
                    null
                }
                !streaming && started != null -> window.close(now, ConnectionSample.Trigger.STOPPED, stream())
                streaming && started != null && now - started >= WINDOW_MS ->
                    window.roll(now, ConnectionSample.Trigger.PERIODIC, stream(), network())
                else -> null
            }
        }
        sample?.let(::store)
    }

    /** A rebuffer started or ended (`LatencyPlayerListener`). */
    fun onStall(stalled: Boolean) {
        synchronized(lock) { window.onStall(stalled, clock.nowMs()) }
    }

    private fun roll(trigger: ConnectionSample.Trigger) {
        val sample = synchronized(lock) {
            if (!window.isOpen) return
            window.roll(clock.nowMs(), trigger, stream(), network())
        }
        sample?.let(::store)
    }

    // ---- from the data sources, on loader threads ----

    fun newStreamId(): Long = streamIds.incrementAndGet()

    fun onNetworkOpened(stream: Long, responseMs: Long) {
        synchronized(lock) {
            window.onNetworkOpened(stream)
            window.onResponse(responseMs)
        }
    }

    fun onNetworkRead(stream: Long, startNs: Long, endNs: Long, count: Int) {
        synchronized(lock) { window.onNetworkRead(stream, startNs, endNs, count) }
    }

    fun onNetworkClosed(stream: Long) {
        synchronized(lock) { window.onNetworkClosed(stream) }
    }

    fun onCacheRead(bytes: Long) {
        synchronized(lock) { window.onCacheRead(bytes) }
    }

    fun onDownloadRead(bytes: Long) {
        synchronized(lock) { window.onDownloadRead(bytes) }
    }

    // ----

    private fun network(): NetworkContext {
        val state = networkMonitor.state.value
        return NetworkContext(state.kind, state.isMetered, state.isVpn, homeNetworkDetector.atHome.value)
    }

    /** What the playing song was opened as. */
    private fun stream(): StreamContext? {
        val now = playing ?: return null
        val decision = streamResolver.applied.value[now.trackId] ?: return StreamContext(serverId = now.serverId)
        return StreamContext(
            serverId = now.serverId,
            profileId = decision.profile.id,
            codec = decision.profile.codec,
            maxBitrateKbps = decision.profile.maxBitrateKbps.takeIf { it > 0 },
            reason = decision.reason,
        )
    }

    private fun store(sample: ConnectionSample) {
        Log.i(
            TAG,
            "${ConnectionSummary.label(sample.network)} ${sample.durationMs / 1_000} s (${sample.trigger.name.lowercase()}): " +
                "${sample.throughputKbps?.let(ConnectionSummary::rate) ?: "unmeasured"} from ${sample.networkBytes / 1024} KB, " +
                "response ${sample.responseMs ?: "-"} ms, ${sample.stalls} stalls ${sample.stallMs} ms, " +
                "${sample.codec ?: "?"}${sample.maxBitrateKbps?.let { " $it" } ?: ""}, source ${sample.source}${if (sample.capped) ", capped" else ""}",
        )
        scope.launch(Dispatchers.IO) {
            runCatching { repository.record(sample) }
                .onSuccess { uploader.onStored(sample.trigger) }
                .onFailure { Log.w(TAG, "Couldn't store a connection sample: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "PolyphonyConnection"
        const val WINDOW_MS = 30_000L
    }
}
