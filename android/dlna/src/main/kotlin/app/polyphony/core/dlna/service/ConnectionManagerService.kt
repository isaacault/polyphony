package app.polyphony.core.dlna.service

import app.polyphony.core.dlna.soap.SoapClient
import app.polyphony.core.dlna.upnp.UpnpService

data class ProtocolInfoResult(val source: Set<String>, val sink: Set<String>)

/** ConnectionManager:1 — only GetProtocolInfo is needed to learn what the renderer can play. */
class ConnectionManagerService(private val soap: SoapClient, val service: UpnpService) {
    suspend fun getProtocolInfo(): ProtocolInfoResult {
        val out = soap.call(service, "GetProtocolInfo")
        return ProtocolInfoResult(
            source = ProtocolInfo.parseMimeTypes(out["Source"]),
            sink = ProtocolInfo.parseMimeTypes(out["Sink"]),
        )
    }
}
