package app.polyphony.core.dlna.soap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SoapEnvelopeTest {

    @Test
    fun `builds an escaped envelope`() {
        val xml = SoapEnvelope.build(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            linkedMapOf("InstanceID" to "0", "CurrentURI" to "http://h/s?a=1&b=2", "CurrentURIMetaData" to "<DIDL-Lite>\"x\"</DIDL-Lite>"),
        )
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope"))
        assertTrue(xml.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(xml.contains("<CurrentURI>http://h/s?a=1&amp;b=2</CurrentURI>"))
        assertTrue(xml.contains("<CurrentURIMetaData>&lt;DIDL-Lite&gt;&quot;x&quot;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"))
        assertTrue(xml.endsWith("</u:SetAVTransportURI></s:Body></s:Envelope>"))
    }

    @Test
    fun `parses out arguments`() {
        val xml = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>0:04:12</TrackDuration><TrackURI>http://x/a.flac</TrackURI><RelTime>0:01:02</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        val out = SoapEnvelope.parseResponse(xml, "GetPositionInfo")
        assertEquals("1", out["Track"])
        assertEquals("0:04:12", out["TrackDuration"])
        assertEquals("http://x/a.flac", out["TrackURI"])
        assertEquals("0:01:02", out["RelTime"])
    }

    @Test
    fun `tolerates a missing response wrapper name`() {
        val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <m:WeirdResponse xmlns:m="urn:x"><CurrentVolume>42</CurrentVolume></m:WeirdResponse></s:Body></s:Envelope>"""
        assertEquals("42", SoapEnvelope.parseResponse(xml, "GetVolume")["CurrentVolume"])
    }

    @Test
    fun `parses UPnP faults`() {
        val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>"""
        try {
            SoapEnvelope.parseResponse(xml, "Play")
            fail("expected fault")
        } catch (e: DlnaException) {
            assertEquals(701, e.code)
            assertTrue(e.isTransitionUnavailable)
            assertTrue(e.message!!.contains("Transition not available"))
        }
        val parsed = SoapEnvelope.parseFault(xml)!!
        assertEquals(701, parsed.code)
    }
}
