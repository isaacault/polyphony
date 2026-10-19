package app.polyphony.core.domain.transcode

import javax.inject.Inject
import javax.inject.Singleton

/** Inputs that influence which profile is picked for a stream. */
data class TranscodeContext(
    val network: NetworkState,
    val target: OutputTarget = OutputTarget.Local,
    val latencyDegraded: Boolean = false,
    /** Explicit profile id configured for [target], if the user set one. */
    val deviceProfileId: String? = null,
)

data class TranscodeDecision(
    val profile: TranscodeProfile,
    val reason: Reason,
    val cappedByLatency: Boolean = false,
) {
    enum class Reason(val label: String) {
        DEVICE_OVERRIDE("Device setting"),
        CAST_DEFAULT("Cast default"),
        WIFI_RULE("Wi-Fi rule"),
        CELLULAR_RULE("Mobile data rule"),
        DEFAULT("Default"),
        /** Served from a file on this device; no transcoding decision was involved. */
        DOWNLOADED("Downloaded"),
        /** The server failed to deliver the transcoded stream; original quality was used instead. */
        FALLBACK("Transcoding unavailable"),
    }

    val explanation: String
        get() = buildString {
            append(reason.label)
            if (cappedByLatency) append(" · capped for high latency")
        }
}

/** Pure function from policy + context to a profile. No Android dependencies; fully unit-testable. */
@Singleton
class TranscodeResolver @Inject constructor() {

    fun resolve(
        policy: TranscodePolicy,
        profiles: Collection<TranscodeProfile>,
        context: TranscodeContext,
    ): TranscodeDecision {
        val byId = profiles.associateBy { it.id }
        fun lookup(id: String?): TranscodeProfile? = id?.let { byId[it] ?: TranscodeProfile.builtIn(it) }

        val fallback = lookup(policy.defaultProfileId) ?: TranscodeProfile.ORIGINAL

        var decision: TranscodeDecision? = null
        if (context.target.isRemote) {
            lookup(context.deviceProfileId)?.let { decision = TranscodeDecision(it, TranscodeDecision.Reason.DEVICE_OVERRIDE) }
            if (decision == null) {
                lookup(policy.castDefaultProfileId)?.let { decision = TranscodeDecision(it, TranscodeDecision.Reason.CAST_DEFAULT) }
            }
        }
        if (decision == null) {
            decision = resolveNetworkRule(policy, context.network, ::lookup, fallback)
        }

        var result = decision!!
        val latency = policy.adaptiveLatency
        val latencyApplies = context.latencyDegraded && latency.enabled &&
            (!context.target.isRemote || latency.applyToRemoteOutputs)
        if (latencyApplies) {
            val cap = lookup(latency.highLatencyProfileId)
            if (cap != null && cap.qualityRank < result.profile.qualityRank) {
                result = result.copy(profile = cap, cappedByLatency = true)
            }
        }
        return result
    }

    private fun resolveNetworkRule(
        policy: TranscodePolicy,
        network: NetworkState,
        lookup: (String?) -> TranscodeProfile?,
        fallback: TranscodeProfile,
    ): TranscodeDecision {
        val treatAsCellular = network.kind == NetworkKind.CELLULAR ||
            (network.isMetered && policy.meteredWifiAsCellular && network.kind != NetworkKind.NONE)
        return when {
            treatAsCellular -> lookup(policy.cellularProfileId)
                ?.let { TranscodeDecision(it, TranscodeDecision.Reason.CELLULAR_RULE) }
            network.kind == NetworkKind.WIFI || network.kind == NetworkKind.ETHERNET -> lookup(policy.wifiProfileId)
                ?.let { TranscodeDecision(it, TranscodeDecision.Reason.WIFI_RULE) }
            else -> null
        } ?: TranscodeDecision(fallback, TranscodeDecision.Reason.DEFAULT)
    }
}
