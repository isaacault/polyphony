package app.polyphony.core.dlna.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpMessageTest {

    @Test
    fun `parses an M-SEARCH response`() {
        val raw = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
            "LOCATION: http://192.168.1.20:49152/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:12345678-abcd-0000-0000-000000000001::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val m = SsdpMessage.parse(raw)!!
        assertEquals(SsdpMessage.Kind.RESPONSE, m.kind)
        assertEquals("http://192.168.1.20:49152/description.xml", m.location)
        assertEquals("uuid:12345678-abcd-0000-0000-000000000001", m.udn)
        assertEquals(1800, m.maxAgeSeconds)
        assertTrue(m.isMediaRenderer)
    }

    @Test
    fun `parses NOTIFY alive and byebye`() {
        val alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:2\r\n" +
            "NTS: ssdp:alive\r\nLOCATION: http://10.0.0.5/dd.xml\r\nUSN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:2\r\nCache-Control: max-age = 120\r\n\r\n"
        val a = SsdpMessage.parse(alive)!!
        assertEquals(SsdpMessage.Kind.NOTIFY_ALIVE, a.kind)
        assertEquals("uuid:abc", a.udn)
        assertEquals(120, a.maxAgeSeconds)
        assertTrue(a.isMediaRenderer)

        val bye = "NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:byebye\r\nUSN: uuid:abc::upnp:rootdevice\r\n\r\n"
        val b = SsdpMessage.parse(bye)!!
        assertEquals(SsdpMessage.Kind.NOTIFY_BYEBYE, b.kind)
        assertEquals("uuid:abc", b.udn)
    }

    @Test
    fun `ignores M-SEARCH requests and non-renderers`() {
        assertNull(SsdpMessage.parse(SsdpMessage.buildSearch("ssdp:all")))
        val server = "HTTP/1.1 200 OK\r\nLOCATION: http://x/d.xml\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nUSN: uuid:srv::urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        val m = SsdpMessage.parse(server)!!
        assertFalse(m.isMediaRenderer)
        assertNull(SsdpMessage.parse("").also { })
    }

    @Test
    fun `search request is well formed`() {
        val s = SsdpMessage.buildSearch(SsdpMessage.MEDIA_RENDERER_ST, mx = 3)
        assertTrue(s.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(s.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(s.contains("MX: 3\r\n"))
        assertTrue(s.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(s.endsWith("\r\n\r\n"))
    }
}
