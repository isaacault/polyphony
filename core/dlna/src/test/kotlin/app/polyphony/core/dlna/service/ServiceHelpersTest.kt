package app.polyphony.core.dlna.service

import app.polyphony.core.dlna.engine.ResolvedStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHelpersTest {

    @Test
    fun `formats and parses upnp time`() {
        assertEquals("0:00:00", UpnpTime.format(0))
        assertEquals("0:03:25", UpnpTime.format(205_000))
        assertEquals("1:02:03.250", UpnpTime.format(3_723_250, withMillis = true))
        assertEquals(205_000L, UpnpTime.parse("0:03:25"))
        assertEquals(205_500L, UpnpTime.parse("00:03:25.5"))
        assertEquals(3_723_250L, UpnpTime.parse("1:02:03.250"))
        assertEquals(125_000L, UpnpTime.parse("2:05"))
        assertEquals(7_000L, UpnpTime.parse("7"))
        assertEquals(10_500L, UpnpTime.parse("0:00:10.1/2"))
        assertEquals(10_000L, UpnpTime.parse("0:00:10.0/1"))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse(""))
        assertNull(UpnpTime.parse("abc"))
        assertNull(UpnpTime.parse(null))
    }

    @Test
    fun `parses protocol info sinks`() {
        val sink = "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,rtsp-rtp-udp:*:audio/mpeg:*,http-get:*:*:*, http-get:*:audio/ogg;codecs=opus:*"
        val mimes = ProtocolInfo.parseMimeTypes(sink)
        assertEquals(setOf("audio/mpeg", "audio/flac", "audio/x-flac", "audio/ogg;codecs=opus"), mimes)
        assertTrue(ProtocolInfo.parseMimeTypes(null).isEmpty())
        val support = RendererFormatSupport.from(mimes)
        assertTrue(support.flac)
        assertTrue(support.mp3)
        assertTrue(support.opus)
        assertFalse(support.aac)
        assertFalse(support.unknown)
        assertEquals(listOf("FLAC", "MP3", "OPUS"), support.preferredCodecs)
        assertTrue(support.supports("audio/flac"))
        assertFalse(support.supports("audio/mp4"))
        assertTrue(RendererFormatSupport.from(emptySet()).unknown)
        assertEquals("MP3", RendererFormatSupport.from(setOf("video/mp4")).preferredCodecs.first())
    }

    @Test
    fun `protocol info strings carry DLNA profile names`() {
        assertEquals("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", ProtocolInfo.forMime("audio/mpeg"))
        assertTrue(ProtocolInfo.forMime("audio/mp4").contains("DLNA.ORG_PN=AAC_ISO_320;"))
        assertTrue(ProtocolInfo.forMime("audio/wav").contains("DLNA.ORG_PN=LPCM;"))
        assertFalse(ProtocolInfo.forMime("audio/flac").contains("DLNA.ORG_PN"))
    }

    @Test
    fun `builds escaped DIDL-Lite`() {
        val didl = DidlLite.build(
            ResolvedStream(
                url = "http://nas:4533/rest/stream?id=1&u=a",
                mimeType = "audio/flac",
                durationMs = 245_500,
                title = "Rock & Roll <Live>",
                artist = "The \"Band\"",
                album = "Greatest",
                artworkUrl = "http://nas/art?id=1&size=600",
                year = 1999,
                trackNumber = 7,
            ),
            itemId = "42",
        )
        assertTrue(didl.startsWith("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""))
        assertTrue(didl.contains("<item id=\"42\" parentID=\"-1\" restricted=\"1\">"))
        assertTrue(didl.contains("<dc:title>Rock &amp; Roll &lt;Live&gt;</dc:title>"))
        assertTrue(didl.contains("<upnp:artist>The &quot;Band&quot;</upnp:artist>"))
        assertTrue(didl.contains("<upnp:albumArtURI>http://nas/art?id=1&amp;size=600</upnp:albumArtURI>"))
        assertTrue(didl.contains("<dc:date>1999-01-01</dc:date>"))
        assertTrue(didl.contains("<upnp:originalTrackNumber>7</upnp:originalTrackNumber>"))
        assertTrue(didl.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertTrue(didl.contains("<res protocolInfo=\"http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\" duration=\"0:04:05.500\">http://nas:4533/rest/stream?id=1&amp;u=a</res>"))
        assertTrue(didl.endsWith("</item></DIDL-Lite>"))
    }
}
