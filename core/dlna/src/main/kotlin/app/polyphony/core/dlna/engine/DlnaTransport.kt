package app.polyphony.core.dlna.engine

import app.polyphony.core.dlna.service.AvTransportService
import app.polyphony.core.dlna.service.RenderingControlService
import app.polyphony.core.dlna.service.TransportState
import app.polyphony.core.dlna.soap.DlnaException

data class TransportPosition(val trackUri: String?, val positionMs: Long?, val durationMs: Long?)

/** The slice of a renderer the [DlnaPlaybackEngine] drives. Fakeable for tests. */
interface DlnaTransport {
    suspend fun setUri(url: String, metadata: String)
    /** May throw [DlnaException] when the renderer does not implement SetNextAVTransportURI. */
    suspend fun setNextUri(url: String, metadata: String)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionMs: Long)
    suspend fun positionInfo(): TransportPosition
    suspend fun transportState(): TransportState
    suspend fun setVolume(percent: Int)
    suspend fun getVolume(): Int?
    suspend fun setMute(muted: Boolean) {}
}

/** Real transport over AVTransport + RenderingControl SOAP services. */
class RendererTransport(
    private val avTransport: AvTransportService,
    private val renderingControl: RenderingControlService?,
) : DlnaTransport {
    override suspend fun setUri(url: String, metadata: String) = avTransport.setUri(url, metadata)
    override suspend fun setNextUri(url: String, metadata: String) = avTransport.setNextUri(url, metadata)
    override suspend fun play() = avTransport.play()
    override suspend fun pause() = avTransport.pause()
    override suspend fun stop() = avTransport.stop()
    override suspend fun seek(positionMs: Long) = avTransport.seek(positionMs)
    override suspend fun positionInfo(): TransportPosition {
        val p = avTransport.positionInfo()
        return TransportPosition(p.trackUri, p.relTimeMs, p.trackDurationMs)
    }
    override suspend fun transportState(): TransportState = avTransport.transportInfo().state
    override suspend fun setVolume(percent: Int) {
        renderingControl?.setVolume(percent) ?: throw DlnaException(0, "Renderer has no RenderingControl service")
    }
    override suspend fun getVolume(): Int? = renderingControl?.getVolume()
    override suspend fun setMute(muted: Boolean) {
        renderingControl?.setMute(muted)
    }
}
