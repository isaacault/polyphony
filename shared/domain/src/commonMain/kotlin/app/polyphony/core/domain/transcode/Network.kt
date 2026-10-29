package app.polyphony.core.domain.transcode

enum class NetworkKind { WIFI, ETHERNET, CELLULAR, OTHER, NONE }

data class NetworkState(
    val kind: NetworkKind,
    val isMetered: Boolean,
    val isVpn: Boolean = false,
    /** Round-trip estimate reported by the platform, when available. */
    val estimatedRttMs: Int? = null,
) {
    val isOnline: Boolean get() = kind != NetworkKind.NONE
    val isUnmeteredWifi: Boolean get() = (kind == NetworkKind.WIFI || kind == NetworkKind.ETHERNET) && !isMetered

    companion object {
        val UNKNOWN = NetworkState(NetworkKind.OTHER, isMetered = false)
        val OFFLINE = NetworkState(NetworkKind.NONE, isMetered = false)
    }
}
