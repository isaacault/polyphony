package app.polyphony.core.dlna.service

import app.polyphony.core.dlna.engine.ResolvedStream
import app.polyphony.core.dlna.upnp.UpnpXml

/** DIDL-Lite metadata for a single music track, as renderers expect in SetAVTransportURI. */
object DidlLite {

    fun build(stream: ResolvedStream, itemId: String = "0", parentId: String = "-1"): String {
        val e = UpnpXml::escape
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
            append("<item id=\"").append(e(itemId)).append("\" parentID=\"").append(e(parentId)).append("\" restricted=\"1\">")
            append("<dc:title>").append(e(stream.title.ifBlank { "Unknown" })).append("</dc:title>")
            stream.artist?.takeIf { it.isNotBlank() }?.let {
                append("<upnp:artist>").append(e(it)).append("</upnp:artist>")
                append("<dc:creator>").append(e(it)).append("</dc:creator>")
            }
            stream.album?.takeIf { it.isNotBlank() }?.let { append("<upnp:album>").append(e(it)).append("</upnp:album>") }
            stream.artworkUrl?.takeIf { it.isNotBlank() }?.let { append("<upnp:albumArtURI>").append(e(it)).append("</upnp:albumArtURI>") }
            stream.year?.let { append("<dc:date>").append(it).append("-01-01</dc:date>") }
            stream.trackNumber?.let { append("<upnp:originalTrackNumber>").append(it).append("</upnp:originalTrackNumber>") }
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res protocolInfo=\"").append(e(ProtocolInfo.forMime(stream.mimeType))).append('"')
            stream.durationMs?.takeIf { it > 0 }?.let { append(" duration=\"").append(UpnpTime.format(it, withMillis = true)).append('"') }
            append('>').append(e(stream.url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }
}
