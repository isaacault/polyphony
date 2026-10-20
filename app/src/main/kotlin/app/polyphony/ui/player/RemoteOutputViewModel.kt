package app.polyphony.ui.player

import android.app.Activity
import android.content.Context
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import app.polyphony.platform.LocalNetworkAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.DlnaController
import app.polyphony.core.domain.playback.DlnaRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Backs the output picker: Cast session state plus UPnP/DLNA discovery and session control. */
@HiltViewModel
class RemoteOutputViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castController: CastController,
    private val dlnaController: DlnaController,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** Why discovery may be failing right now: VPN active, or not on Wi-Fi. */
    val networkHint: StateFlow<NetworkHint?> = networkMonitor.state
        .map { n ->
            when {
                n.kind != NetworkKind.WIFI && n.kind != NetworkKind.ETHERNET -> NetworkHint.NOT_ON_WIFI
                n.isVpn -> NetworkHint.VPN_ACTIVE
                else -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    enum class NetworkHint { NOT_ON_WIFI, VPN_ACTIVE }

    private val _localNetworkDenied = MutableStateFlow(!LocalNetworkAccess.isGranted(context))

    /** True on an OS that gates LAN access (Android 17, GrapheneOS) while the user has not allowed it: discovery cannot work. */
    val localNetworkDenied: StateFlow<Boolean> = _localNetworkDenied.asStateFlow()
    private var askedForLocalNetwork = false

    /** Re-check after the permission dialog or a trip to app settings; restarts discovery once access appears. */
    fun refreshLocalNetworkAccess() {
        val denied = !LocalNetworkAccess.isGranted(context)
        if (_localNetworkDenied.value && !denied) {
            stopDiscovery()
            startDiscovery()
        }
        _localNetworkDenied.value = denied
    }

    /** Ask for local network access; after a permanent denial, send the user to app settings instead. */
    fun allowLocalNetwork(activity: Activity?, launch: (String) -> Unit) {
        val permanentlyDenied = askedForLocalNetwork && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalNetworkAccess.PERMISSION)
        if (permanentlyDenied) {
            LocalNetworkAccess.openAppSettings(context)
        } else {
            askedForLocalNetwork = true
            launch(LocalNetworkAccess.PERMISSION)
        }
    }

    val cast: StateFlow<CastState> = castController.state
    val dlna: StateFlow<CastState> = dlnaController.state

    val renderers: StateFlow<ImmutableList<DlnaRenderer>> = dlnaController.renderers
        .map { list -> list.sortedBy { it.friendlyName.lowercase() }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    fun startDiscovery() = runCatching { dlnaController.startDiscovery() }
    fun stopDiscovery() = runCatching { dlnaController.stopDiscovery() }

    fun connectDlna(udn: String) {
        if (castController.state.value.isActiveSession) castController.disconnect()
        dlnaController.connect(udn)
    }

    fun disconnectDlna() = dlnaController.disconnect()
    fun disconnectCast() = castController.disconnect()

    fun setRemoteVolume(volume: Float) {
        when {
            dlnaController.state.value.isConnected -> dlnaController.setVolume(volume)
            castController.state.value.isConnected -> castController.setVolume(volume)
        }
    }
}
