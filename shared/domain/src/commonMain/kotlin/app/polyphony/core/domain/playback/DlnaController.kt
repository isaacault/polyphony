package app.polyphony.core.domain.playback

import app.polyphony.core.domain.transcode.OutputTarget
import kotlinx.coroutines.flow.StateFlow

/** A UPnP AV MediaRenderer found on the local network. */
data class DlnaRenderer(
    val udn: String,
    val friendlyName: String,
    val modelName: String? = null,
    val manufacturer: String? = null,
    val iconUrl: String? = null,
    /** MIME types advertised by ConnectionManager.GetProtocolInfo (sink), lower-case. */
    val supportedMimeTypes: Set<String> = emptySet(),
    val lastSeenAt: Long = 0L,
) {
    val target: OutputTarget.Dlna get() = OutputTarget.Dlna(udn, friendlyName, modelName)
}

/**
 * Discovery and session control for UPnP/DLNA renderers. The session itself is driven by a
 * Media3 `Player` implementation inside the playback service; this surface is what the UI uses.
 */
interface DlnaController {
    val renderers: StateFlow<List<DlnaRenderer>>
    /** Connected / connecting state for the DLNA output, mirrors [CastController.state]. */
    val state: StateFlow<CastState>
    fun startDiscovery()
    fun stopDiscovery()
    fun connect(udn: String)
    fun disconnect()
    fun setVolume(volume: Float)
}
