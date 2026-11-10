package app.polyphony.core.domain.connection

import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeDecision
import kotlinx.serialization.Serializable

/**
 * What the connection was like over one window of streaming (about 30 s, or until the network
 * changed or playback stopped), as the phone measured it (#185 phase 1). Stored on the phone and
 * shaped to be sent as it is to continuo later ([ConnectionSampleBatch]).
 */
@Serializable
data class ConnectionSample(
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** Why the window ended. */
    val trigger: Trigger,
    /** The network the whole window was on (a change of network ends the window). */
    val network: NetworkKind,
    val metered: Boolean,
    val vpn: Boolean = false,
    /** On the home network (`HomeNetworkDetector`); null when it couldn't be told. */
    val atHome: Boolean? = null,
    /**
     * Median time from asking for a stream to its first response, over the requests opened in the
     * window: the round trip plus the server's start-up (a transcode starting). Null when none was.
     */
    val responseMs: Int? = null,
    /** Bytes read off the network for streams (the player's and the fetching ahead). */
    val networkBytes: Long = 0,
    /** The time those reads took, as [ThroughputMeter] counts it. */
    val networkMs: Long = 0,
    /** [networkBytes] over [networkMs]; null when too little was read to tell. */
    val throughputKbps: Int? = null,
    /** Bytes the player read from the stream cache instead of the network (not in the throughput). */
    val cachedBytes: Long = 0,
    /** Bytes the player read from downloaded files (not in the throughput). */
    val downloadedBytes: Long = 0,
    /** Rebuffers that started in the window. */
    val stalls: Int = 0,
    /** Time spent rebuffering in the window, including a stall that began before it. */
    val stallMs: Long = 0,
    val serverId: String? = null,
    /** The transcoding profile the playing song was opened with. */
    val profileId: String? = null,
    val codec: AudioCodec? = null,
    /** The profile's bitrate cap; null for none. */
    val maxBitrateKbps: Int? = null,
    val reason: TranscodeDecision.Reason? = null,
    /** `LatencyMonitor`'s quality cap was in force at some point in the window. */
    val capped: Boolean = false,
    /** How long it was in force in the window. */
    val cappedMs: Long = 0,
) {
    enum class Trigger { PERIODIC, NETWORK_CHANGE, STOPPED }

    /** Where the bytes the window read came from. */
    enum class Source { NETWORK, CACHE, DOWNLOAD, MIXED, NONE }

    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0)

    val source: Source
        get() {
            val from = buildList {
                if (networkBytes > 0) add(Source.NETWORK)
                if (cachedBytes > 0) add(Source.CACHE)
                if (downloadedBytes > 0) add(Source.DOWNLOAD)
            }
            return when (from.size) {
                0 -> Source.NONE
                1 -> from.single()
                else -> Source.MIXED
            }
        }
}

/**
 * The body of a future `POST /v1/devices/{deviceId}/connection` (#185): the samples not sent yet,
 * oldest first. Nothing sends it yet.
 */
@Serializable
data class ConnectionSampleBatch(val samples: List<ConnectionSample>)
