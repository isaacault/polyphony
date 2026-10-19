package app.polyphony.core.dlna.service

import app.polyphony.core.dlna.soap.SoapClient
import app.polyphony.core.dlna.upnp.UpnpService

/** RenderingControl:1 volume/mute on the Master channel. */
class RenderingControlService(private val soap: SoapClient, val service: UpnpService) {

    suspend fun getVolume(): Int? {
        val out = soap.call(service, "GetVolume", mapOf("InstanceID" to "0", "Channel" to "Master"))
        return out["CurrentVolume"]?.trim()?.toIntOrNull()?.coerceIn(0, 100)
    }

    suspend fun setVolume(percent: Int) {
        soap.call(service, "SetVolume", mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString()))
    }

    suspend fun getMute(): Boolean? {
        val out = soap.call(service, "GetMute", mapOf("InstanceID" to "0", "Channel" to "Master"))
        return when (out["CurrentMute"]?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    suspend fun setMute(muted: Boolean) {
        soap.call(service, "SetMute", mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"))
    }
}
