package app.polyphony.core.domain.transcode

import kotlinx.serialization.Serializable

/**
 * User-configurable rules that decide which [TranscodeProfile] applies in a given situation.
 *
 * Resolution order (see [TranscodeResolver]):
 *  1. Explicit per-device override for the current remote output (Cast / DLNA).
 *  2. [castDefaultProfileId] when playing to any remote output.
 *  3. Network rule: cellular / metered → [cellularProfileId], Wi-Fi / Ethernet → [wifiProfileId].
 *  4. [defaultProfileId].
 *  Then, if latency degradation is active and [AdaptiveLatencySettings.enabled], the high-latency
 *  profile caps whatever was selected (the lower quality of the two wins).
 */
@Serializable
data class TranscodePolicy(
    val defaultProfileId: String = TranscodeProfile.ORIGINAL_ID,
    /** null = fall through to [defaultProfileId]. */
    val wifiProfileId: String? = null,
    val cellularProfileId: String? = TranscodeProfile.BALANCED_ID,
    /** Treat metered Wi-Fi (hotspots) like cellular. */
    val meteredWifiAsCellular: Boolean = true,
    val castDefaultProfileId: String? = null,
    val adaptiveLatency: AdaptiveLatencySettings = AdaptiveLatencySettings(),
    /**
     * When the active profile changes mid-track (network switch, latency cap), restart the current
     * track at the same position with the new quality instead of waiting for the next track.
     */
    val reloadCurrentTrackOnChange: Boolean = true,
)

@Serializable
data class AdaptiveLatencySettings(
    val enabled: Boolean = true,
    /** Profile applied as a cap while the connection is considered degraded. */
    val highLatencyProfileId: String? = TranscodeProfile.BALANCED_ID,
    /** Number of rebuffer events within [windowSeconds] that trips the degraded state. */
    val stallThreshold: Int = 3,
    val windowSeconds: Int = 120,
    /** A single stall longer than this also trips the degraded state. */
    val longStallMs: Long = 4_000,
    /** Seconds without stalls before quality is restored automatically. 0 = manual restore only. */
    val recoverAfterSeconds: Int = 600,
    /** Whether the latency cap also applies when casting. */
    val applyToRemoteOutputs: Boolean = true,
)
