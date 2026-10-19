package app.polyphony.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.transcode.NetworkState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitorImpl @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkMonitor {

    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(readCurrent())
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _state.value = networkCapabilities.toNetworkState()
        }

        override fun onLost(network: Network) {
            _state.value = readCurrent(exclude = network)
        }

        override fun onUnavailable() {
            _state.value = NetworkState.OFFLINE
        }
    }

    init {
        try {
            connectivity?.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot observe connectivity", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot observe connectivity", e)
        }
    }

    private fun readCurrent(exclude: Network? = null): NetworkState {
        val cm = connectivity ?: return NetworkState.UNKNOWN
        return try {
            val active = cm.activeNetwork
            if (active == null || active == exclude) NetworkState.OFFLINE
            else cm.getNetworkCapabilities(active)?.toNetworkState() ?: NetworkState.OFFLINE
        } catch (e: RuntimeException) {
            NetworkState.UNKNOWN
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

internal fun NetworkCapabilities.toNetworkState(): NetworkState = NetworkStateMapper.map(
    NetworkStateMapper.Flags(
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        wifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        ethernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        vpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        notMetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        temporarilyNotMetered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
    ),
)
