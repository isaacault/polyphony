package app.polyphony.core.data.network

import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.NetworkState

/** Pure derivation of [NetworkState] from capability flags, so it can be unit-tested on the JVM. */
object NetworkStateMapper {

    data class Flags(
        val hasInternet: Boolean = true,
        val wifi: Boolean = false,
        val ethernet: Boolean = false,
        val cellular: Boolean = false,
        val vpn: Boolean = false,
        val notMetered: Boolean = false,
        val temporarilyNotMetered: Boolean = false,
    )

    fun map(flags: Flags?): NetworkState {
        if (flags == null || !flags.hasInternet) return NetworkState.OFFLINE
        val kind = when {
            flags.ethernet -> NetworkKind.ETHERNET
            flags.wifi -> NetworkKind.WIFI
            flags.cellular -> NetworkKind.CELLULAR
            else -> NetworkKind.OTHER
        }
        val metered = !(flags.notMetered || flags.temporarilyNotMetered)
        return NetworkState(kind = kind, isMetered = metered, isVpn = flags.vpn)
    }
}
