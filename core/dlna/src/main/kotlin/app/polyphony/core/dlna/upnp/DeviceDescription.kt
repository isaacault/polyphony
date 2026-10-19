package app.polyphony.core.dlna.upnp

import app.polyphony.core.dlna.upnp.UpnpXml.child
import app.polyphony.core.dlna.upnp.UpnpXml.childElements
import app.polyphony.core.dlna.upnp.UpnpXml.childText
import app.polyphony.core.dlna.upnp.UpnpXml.children
import app.polyphony.core.dlna.upnp.UpnpXml.findFirst
import org.w3c.dom.Element
import java.net.URI

data class UpnpService(
    val serviceType: String,
    val serviceId: String?,
    val controlUrl: String,
    val eventSubUrl: String?,
) {
    val isAvTransport: Boolean get() = serviceType.contains(":service:AVTransport:", ignoreCase = true)
    val isRenderingControl: Boolean get() = serviceType.contains(":service:RenderingControl:", ignoreCase = true)
    val isConnectionManager: Boolean get() = serviceType.contains(":service:ConnectionManager:", ignoreCase = true)
}

data class UpnpDevice(
    val udn: String,
    val deviceType: String?,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val iconUrl: String?,
    val location: String,
    val services: List<UpnpService>,
) {
    val avTransport: UpnpService? get() = services.firstOrNull { it.isAvTransport }
    val renderingControl: UpnpService? get() = services.firstOrNull { it.isRenderingControl }
    val connectionManager: UpnpService? get() = services.firstOrNull { it.isConnectionManager }
}

/** Parses a UPnP device description document into the renderer we can control. */
object DeviceDescriptionParser {

    /**
     * @param location the URL the description was fetched from; used to resolve relative URLs when
     *   the document carries no `URLBase`.
     * @return null when the document has no device exposing an AVTransport service.
     */
    fun parse(xml: String, location: String): UpnpDevice? {
        val doc = UpnpXml.parse(xml) ?: return null
        val root = doc.documentElement ?: return null
        val base = root.childText("URLBase")?.let { normaliseBase(it) } ?: location
        val rootDevice = root.child("device") ?: root.findFirst("device") ?: return null
        val candidates = ArrayList<UpnpDevice>()
        collect(rootDevice, base, location, candidates)
        return candidates.firstOrNull { it.avTransport != null && it.deviceType?.contains("MediaRenderer", true) == true }
            ?: candidates.firstOrNull { it.avTransport != null }
    }

    private fun collect(deviceEl: Element, base: String, location: String, out: MutableList<UpnpDevice>) {
        val services = deviceEl.child("serviceList")?.children("service").orEmpty().mapNotNull { s ->
            val type = s.childText("serviceType") ?: return@mapNotNull null
            val control = s.childText("controlURL") ?: return@mapNotNull null
            UpnpService(
                serviceType = type,
                serviceId = s.childText("serviceId"),
                controlUrl = resolve(base, control),
                eventSubUrl = s.childText("eventSubURL")?.let { resolve(base, it) },
            )
        }
        val udn = deviceEl.childText("UDN")
        val friendly = deviceEl.childText("friendlyName")
        if (udn != null && friendly != null) {
            out.add(
                UpnpDevice(
                    udn = udn,
                    deviceType = deviceEl.childText("deviceType"),
                    friendlyName = friendly,
                    manufacturer = deviceEl.childText("manufacturer"),
                    modelName = deviceEl.childText("modelName"),
                    iconUrl = bestIcon(deviceEl, base),
                    location = location,
                    services = services,
                ),
            )
        }
        deviceEl.child("deviceList")?.children("device")?.forEach { collect(it, base, location, out) }
    }

    private fun bestIcon(deviceEl: Element, base: String): String? {
        val icons = deviceEl.child("iconList")?.children("icon").orEmpty()
        return icons.mapNotNull { icon ->
            val url = icon.childText("url") ?: return@mapNotNull null
            val mime = icon.childText("mimetype")?.lowercase().orEmpty()
            val w = icon.childText("width")?.toIntOrNull() ?: 0
            val h = icon.childText("height")?.toIntOrNull() ?: 0
            val score = (if (mime.contains("png")) 1_000_000 else 0) + w * h
            score to resolve(base, url)
        }.maxByOrNull { it.first }?.second
    }

    private fun normaliseBase(urlBase: String): String =
        if (urlBase.endsWith("/")) urlBase else "$urlBase/"

    internal fun resolve(base: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        return try {
            URI(base).resolve(trimmed.replace(" ", "%20")).toString()
        } catch (_: Throwable) {
            // Fall back to naive joining for base URLs that URI rejects.
            val root = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore("/")
            if (trimmed.startsWith("/")) root + trimmed else "$root/$trimmed"
        }
    }

    private fun Element.allDevices(): List<Element> = childElements().filter { it.localName == "device" }
}
