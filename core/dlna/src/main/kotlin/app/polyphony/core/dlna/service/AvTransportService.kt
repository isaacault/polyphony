package app.polyphony.core.dlna.service

import app.polyphony.core.dlna.soap.SoapClient
import app.polyphony.core.dlna.upnp.UpnpService

enum class TransportState { PLAYING, PAUSED_PLAYBACK, PAUSED_RECORDING, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT, RECORDING, UNKNOWN;

    companion object {
        fun parse(value: String?): TransportState = when (value?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> PAUSED_PLAYBACK
            "PAUSED_RECORDING" -> PAUSED_RECORDING
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            "NO_MEDIA_PRESENT" -> NO_MEDIA_PRESENT
            "RECORDING" -> RECORDING
            else -> UNKNOWN
        }
    }
}

data class TransportInfo(val state: TransportState, val status: String?, val speed: String?)

data class PositionInfo(
    val track: Int,
    val trackDurationMs: Long?,
    val trackUri: String?,
    val relTimeMs: Long?,
    val absTimeMs: Long?,
)

data class MediaInfo(val nrTracks: Int, val mediaDurationMs: Long?, val currentUri: String?, val nextUri: String?)

/** AVTransport:1 actions we use. All calls use InstanceID 0. */
class AvTransportService(private val soap: SoapClient, val service: UpnpService) {

    suspend fun setUri(uri: String, metadata: String) {
        soap.call(service, "SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to metadata))
    }

    suspend fun setNextUri(uri: String, metadata: String) {
        soap.call(service, "SetNextAVTransportURI", mapOf("InstanceID" to "0", "NextURI" to uri, "NextURIMetaData" to metadata))
    }

    suspend fun play() {
        soap.call(service, "Play", mapOf("InstanceID" to "0", "Speed" to "1"))
    }

    suspend fun pause() {
        soap.call(service, "Pause", mapOf("InstanceID" to "0"))
    }

    suspend fun stop() {
        soap.call(service, "Stop", mapOf("InstanceID" to "0"))
    }

    suspend fun seek(positionMs: Long) {
        soap.call(service, "Seek", mapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to UpnpTime.format(positionMs)))
    }

    suspend fun positionInfo(): PositionInfo {
        val out = soap.call(service, "GetPositionInfo", mapOf("InstanceID" to "0"))
        return PositionInfo(
            track = out["Track"]?.toIntOrNull() ?: 0,
            trackDurationMs = UpnpTime.parse(out["TrackDuration"])?.takeIf { it > 0 },
            trackUri = out["TrackURI"]?.takeIf { it.isNotBlank() },
            relTimeMs = UpnpTime.parse(out["RelTime"])?.takeIf { it >= 0 },
            absTimeMs = UpnpTime.parse(out["AbsTime"])?.takeIf { it >= 0 },
        )
    }

    suspend fun transportInfo(): TransportInfo {
        val out = soap.call(service, "GetTransportInfo", mapOf("InstanceID" to "0"))
        return TransportInfo(TransportState.parse(out["CurrentTransportState"]), out["CurrentTransportStatus"], out["CurrentSpeed"])
    }

    suspend fun mediaInfo(): MediaInfo {
        val out = soap.call(service, "GetMediaInfo", mapOf("InstanceID" to "0"))
        return MediaInfo(
            nrTracks = out["NrTracks"]?.toIntOrNull() ?: 0,
            mediaDurationMs = UpnpTime.parse(out["MediaDuration"])?.takeIf { it > 0 },
            currentUri = out["CurrentURI"]?.takeIf { it.isNotBlank() },
            nextUri = out["NextURI"]?.takeIf { it.isNotBlank() },
        )
    }
}
