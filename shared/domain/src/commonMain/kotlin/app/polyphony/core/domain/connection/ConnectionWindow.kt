package app.polyphony.core.domain.connection

import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeDecision

/** The network a window is on. */
data class NetworkContext(
    val kind: NetworkKind,
    val metered: Boolean,
    val vpn: Boolean = false,
    val atHome: Boolean? = null,
)

/** What the playing song was streamed as when a window closed. */
data class StreamContext(
    val serverId: String? = null,
    val profileId: String? = null,
    val codec: AudioCodec? = null,
    val maxBitrateKbps: Int? = null,
    val reason: TranscodeDecision.Reason? = null,
)

/**
 * Gathers one [ConnectionSample]: open it when streaming starts, feed it reads, stalls and the
 * latency cap, and [roll] it every 30 s or on a network change ([close] when streaming stops).
 * Stall and cap states are followed while the window is closed too, so a stall that runs across a
 * boundary counts once, in the window it began in, and its time in each window it covers.
 * Pure and not thread-safe: the caller serialises calls. Wall-clock milliseconds except reads.
 */
class ConnectionWindow(private val meter: ThroughputMeter = ThroughputMeter()) {

    private class Span {
        var on = false
            private set
        private var sinceMs = 0L
        private var totalMs = 0L

        /** True when the state changed. */
        fun set(value: Boolean, nowMs: Long, windowStartMs: Long?): Boolean {
            if (value == on) return false
            if (on && windowStartMs != null) totalMs += nowMs - maxOf(sinceMs, windowStartMs)
            on = value
            sinceMs = nowMs
            return true
        }

        fun totalAt(nowMs: Long, windowStartMs: Long): Long =
            totalMs + if (on) nowMs - maxOf(sinceMs, windowStartMs) else 0

        fun resetTotal() {
            totalMs = 0
        }
    }

    /** When the open window began; null while closed. */
    var startedAtMs: Long? = null
        private set
    private var network: NetworkContext? = null
    private val stall = Span()
    private val cap = Span()
    private var stalls = 0
    private var cappedSeen = false
    private val responseTimes = ArrayList<Long>()
    private var cachedBytes = 0L
    private var downloadedBytes = 0L

    val isOpen: Boolean get() = startedAtMs != null

    fun open(nowMs: Long, network: NetworkContext) {
        startedAtMs = nowMs
        this.network = network
        stalls = 0
        cappedSeen = cap.on
        stall.resetTotal()
        cap.resetTotal()
        responseTimes.clear()
        cachedBytes = 0
        downloadedBytes = 0
        meter.reset()
    }

    /**
     * The same network, better known (home or away was found out after the window began). Another
     * network is ignored: the change ends the window instead ([roll]).
     */
    fun updateNetwork(network: NetworkContext) {
        val current = this.network ?: return
        if (current.kind == network.kind && current.metered == network.metered) this.network = network
    }

    fun onStall(stalled: Boolean, nowMs: Long) {
        if (stall.set(stalled, nowMs, startedAtMs) && stalled && isOpen) stalls++
    }

    fun onCapped(capped: Boolean, nowMs: Long) {
        cap.set(capped, nowMs, startedAtMs)
        if (capped && isOpen) cappedSeen = true
    }

    /** A stream request answered after [ms]. */
    fun onResponse(ms: Long) {
        if (isOpen) responseTimes += ms
    }

    fun onNetworkOpened(stream: Long) = meter.onOpened(stream)

    fun onNetworkClosed(stream: Long) = meter.onClosed(stream)

    fun onNetworkRead(stream: Long, startNs: Long, endNs: Long, count: Int) {
        // Followed while closed too, so a stream's runs and pauses are known when the next window opens.
        val before = meter.bytes
        meter.onRead(stream, startNs, endNs, count)
        if (!isOpen && meter.bytes != before) meter.reset()
    }

    fun onCacheRead(bytes: Long) {
        if (isOpen && bytes > 0) cachedBytes += bytes
    }

    fun onDownloadRead(bytes: Long) {
        if (isOpen && bytes > 0) downloadedBytes += bytes
    }

    /**
     * Ends the window at [nowMs]: its sample, or null when it is too short to say anything (under
     * [MIN_WINDOW_MS] without a stall). The window is closed afterwards.
     */
    fun close(nowMs: Long, trigger: ConnectionSample.Trigger, stream: StreamContext?): ConnectionSample? {
        val start = startedAtMs ?: return null
        val net = network ?: return null
        val sample = ConnectionSample(
            startedAtMs = start,
            endedAtMs = nowMs,
            trigger = trigger,
            network = net.kind,
            metered = net.metered,
            vpn = net.vpn,
            atHome = net.atHome,
            responseMs = median(responseTimes)?.toInt(),
            networkBytes = meter.bytes,
            networkMs = meter.busyNs / 1_000_000L,
            throughputKbps = meter.throughputKbps(),
            cachedBytes = cachedBytes,
            downloadedBytes = downloadedBytes,
            stalls = stalls,
            stallMs = stall.totalAt(nowMs, start),
            serverId = stream?.serverId,
            profileId = stream?.profileId,
            codec = stream?.codec,
            maxBitrateKbps = stream?.maxBitrateKbps,
            reason = stream?.reason,
            capped = cappedSeen,
            cappedMs = cap.totalAt(nowMs, start),
        )
        startedAtMs = null
        network = null
        meter.reset()
        return sample.takeIf { it.durationMs >= MIN_WINDOW_MS || it.stalls > 0 }
    }

    /** Closes this window and opens the next one at once, on [next]. */
    fun roll(
        nowMs: Long,
        trigger: ConnectionSample.Trigger,
        stream: StreamContext?,
        next: NetworkContext,
    ): ConnectionSample? = close(nowMs, trigger, stream).also { open(nowMs, next) }

    companion object {
        /** A window shorter than this, without a stall, is a flap or a quick pause, not a sample. */
        const val MIN_WINDOW_MS = 5_000L

        fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
