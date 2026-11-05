package app.polyphony.core.domain.transcode


/** Inputs that influence which profile is picked for a stream. */
data class TranscodeContext(
    val network: NetworkState,
    val target: OutputTarget = OutputTarget.Local,
    val latencyDegraded: Boolean = false,
    /** Explicit profile id configured for [target], if the user set one. */
    val deviceProfileId: String? = null,
    /** On the home network (the server answers at its LAN address); null when unknown. */
    val atHome: Boolean? = null,
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
        /** Wi-Fi away from home, given the mobile data profile. */
        AWAY_RULE("Away from home"),
        DEFAULT("Default"),
        /** Served from a file on this device; no transcoding decision was involved. */
        DOWNLOADED("Downloaded"),
        /** The server failed to deliver the transcoded stream; original quality was used instead. */
        FALLBACK("Transcoding unavailable"),
        /** An already-compressed file streamed as it is rather than re-encoded ([LossySourceRule]). */
        LOSSY_SOURCE("Already compressed"),
    }

    val explanation: String
        get() = buildString {
            append(reason.label)
            if (cappedByLatency) append(" · capped for high latency")
        }
}

/** Pure function from policy + context to a profile. No Android dependencies; fully unit-testable. */
class TranscodeResolver {

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
            decision = resolveNetworkRule(policy, context.network, context.atHome, ::lookup, fallback)
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
        atHome: Boolean?,
        lookup: (String?) -> TranscodeProfile?,
        fallback: TranscodeProfile,
    ): TranscodeDecision {
        val treatAsCellular = network.kind == NetworkKind.CELLULAR ||
            (network.isMetered && policy.meteredWifiAsCellular && network.kind != NetworkKind.NONE)
        val awayOnWifi = policy.awayWifiAsCellular && atHome == false &&
            (network.kind == NetworkKind.WIFI || network.kind == NetworkKind.ETHERNET)
        return when {
            treatAsCellular -> lookup(policy.cellularProfileId)
                ?.let { TranscodeDecision(it, TranscodeDecision.Reason.CELLULAR_RULE) }
            awayOnWifi -> lookup(policy.cellularProfileId)
                ?.let { TranscodeDecision(it, TranscodeDecision.Reason.AWAY_RULE) }
            network.kind == NetworkKind.WIFI || network.kind == NetworkKind.ETHERNET -> lookup(policy.wifiProfileId)
                ?.let { TranscodeDecision(it, TranscodeDecision.Reason.WIFI_RULE) }
            else -> null
        } ?: TranscodeDecision(fallback, TranscodeDecision.Reason.DEFAULT)
    }
}
