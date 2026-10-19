package app.polyphony.core.dlna.soap

import app.polyphony.core.dlna.upnp.UpnpXml
import app.polyphony.core.dlna.upnp.UpnpXml.childElements
import app.polyphony.core.dlna.upnp.UpnpXml.findFirst
import app.polyphony.core.dlna.upnp.UpnpXml.local

/** Builds and parses UPnP SOAP envelopes. Pure; unit-tested. */
object SoapEnvelope {

    fun build(serviceType: String, action: String, args: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(UpnpXml.escape(serviceType)).append("\">")
        for ((name, value) in args) {
            append('<').append(name).append('>').append(UpnpXml.escape(value)).append("</").append(name).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body></s:Envelope>")
    }

    /**
     * Returns the out-arguments of `<u:{action}Response>`. Throws [DlnaException] for SOAP faults.
     * Renderers that omit the Response wrapper or use odd prefixes are tolerated.
     */
    fun parseResponse(xml: String, action: String): Map<String, String> {
        val doc = UpnpXml.parse(xml) ?: throw DlnaException(0, "Unparseable SOAP response for $action")
        val root = doc.documentElement ?: throw DlnaException(0, "Empty SOAP response for $action")
        root.findFirst("Fault")?.let { throw parseFault(it) }
        val response = root.findFirst("${action}Response")
            ?: root.findFirst("Body")?.childElements()?.firstOrNull()
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (child in response.childElements()) {
            out[child.local()] = child.textContent?.trim().orEmpty()
        }
        return out
    }

    fun parseFault(xml: String): DlnaException? {
        val doc = UpnpXml.parse(xml) ?: return null
        val fault = doc.documentElement?.findFirst("Fault") ?: return null
        return parseFault(fault)
    }

    private fun parseFault(fault: org.w3c.dom.Element): DlnaException {
        val code = fault.findFirst("errorCode")?.textContent?.trim()?.toIntOrNull()
        val description = fault.findFirst("errorDescription")?.textContent?.trim()
            ?: fault.findFirst("faultstring")?.textContent?.trim()
            ?: "SOAP fault"
        return DlnaException(code ?: 500, description)
    }
}
