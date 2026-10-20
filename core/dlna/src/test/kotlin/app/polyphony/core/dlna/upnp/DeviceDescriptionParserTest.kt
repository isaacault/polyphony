package app.polyphony.core.dlna.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceDescriptionParserTest {

    private val nested = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
            <friendlyName>Hub</friendlyName>
            <manufacturer>Acme</manufacturer>
            <UDN>uuid:hub</UDN>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Living Room &amp; Kitchen</friendlyName>
                <manufacturer>Acme</manufacturer>
                <modelName>Streamer 2</modelName>
                <UDN>uuid:renderer-1</UDN>
                <iconList>
                  <icon><mimetype>image/jpeg</mimetype><width>120</width><height>120</height><depth>24</depth><url>/icons/big.jpg</url></icon>
                  <icon><mimetype>image/png</mimetype><width>48</width><height>48</height><depth>24</depth><url>icons/small.png</url></icon>
                </iconList>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                    <SCPDURL>/AVTransport/scpd.xml</SCPDURL>
                    <controlURL>/AVTransport/control</controlURL>
                    <eventSubURL>/AVTransport/event</eventSubURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                    <SCPDURL>rc/scpd.xml</SCPDURL>
                    <controlURL>rc/control</controlURL>
                    <eventSubURL>rc/event</eventSubURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                    <SCPDURL>/cm/scpd.xml</SCPDURL>
                    <controlURL>http://192.168.1.20:8080/cm/control</controlURL>
                    <eventSubURL>/cm/event</eventSubURL>
                  </service>
                </serviceList>
              </device>
            </deviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `finds the embedded renderer and resolves relative urls against the location`() {
        val d = DeviceDescriptionParser.parse(nested, "http://192.168.1.20:49152/dev/description.xml")!!
        assertEquals("uuid:renderer-1", d.udn)
        assertEquals("Living Room & Kitchen", d.friendlyName)
        assertEquals("Streamer 2", d.modelName)
        assertEquals("http://192.168.1.20:49152/AVTransport/control", d.avTransport!!.controlUrl)
        assertEquals("http://192.168.1.20:49152/AVTransport/event", d.avTransport!!.eventSubUrl)
        assertEquals("http://192.168.1.20:49152/dev/rc/control", d.renderingControl!!.controlUrl)
        assertEquals("http://192.168.1.20:8080/cm/control", d.connectionManager!!.controlUrl)
        // png preferred over the larger jpeg
        assertEquals("http://192.168.1.20:49152/dev/icons/small.png", d.iconUrl)
    }

    @Test
    fun `honours URLBase when present`() {
        val xml = nested.replace("<specVersion>", "<URLBase>http://10.1.1.1:5000/base/</URLBase><specVersion>")
        val d = DeviceDescriptionParser.parse(xml, "http://192.168.1.20:49152/description.xml")!!
        assertEquals("http://10.1.1.1:5000/AVTransport/control", d.avTransport!!.controlUrl)
        assertEquals("http://10.1.1.1:5000/base/rc/control", d.renderingControl!!.controlUrl)
    }

    @Test
    fun `rejects devices without AVTransport`() {
        val xml = nested.replace("urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:service:Something:1")
        assertNull(DeviceDescriptionParser.parse(xml, "http://192.168.1.20/d.xml"))
    }

    @Test
    fun `tolerates junk`() {
        assertNull(DeviceDescriptionParser.parse("not xml at all", "http://x/d.xml"))
        assertNull(DeviceDescriptionParser.parse("", "http://x/d.xml"))
        assertNotNull(DeviceDescriptionParser.parse("﻿$nested", "http://192.168.1.20/d.xml"))
    }
}

class DeviceDescriptionExplainTest {
    @Test
    fun `explains a document without AVTransport by listing what it found`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
              <friendlyName>Router</friendlyName><UDN>uuid:r</UDN>
              <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service></serviceList>
            </device></root>
        """.trimIndent()
        val failure = DeviceDescriptionParser.parseOrExplain(xml, "http://10.0.0.1/desc.xml").exceptionOrNull()
        org.junit.Assert.assertNotNull(failure)
        val message = failure!!.message.orEmpty()
        org.junit.Assert.assertTrue(message, message.contains("no device with an AVTransport service"))
        org.junit.Assert.assertTrue(message, message.contains("Router [urn:schemas-upnp-org:device:InternetGatewayDevice:1] services=[Layer3Forwarding:1]"))
    }

    @Test
    fun `explains unparseable xml`() {
        val failure = DeviceDescriptionParser.parseOrExplain("<root><device>", "http://10.0.0.1/desc.xml").exceptionOrNull()
        org.junit.Assert.assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("XML did not parse:"))
    }
}

class YamahaRxV483DescriptionTest {
    private val xml = javaClass.classLoader!!.getResourceAsStream("yamaha-rx-v483-desc.xml")!!.bufferedReader().readText()

    @Test
    fun `parses the real RX-V483 description`() {
        val result = DeviceDescriptionParser.parseOrExplain(xml, "http://192.168.0.100:49154/MediaRenderer/desc.xml")
        val device = result.getOrElse { throw AssertionError(it.message, it) }
        assertEquals("Lounge", device.friendlyName)
        assertEquals("uuid:9ab0c000-f668-11de-9976-00a0deffd733", device.udn)
        assertEquals("RX-V483", device.modelName)
        assertEquals("http://192.168.0.100:49154/AVTransport/ctrl", device.avTransport?.controlUrl)
        assertEquals("http://192.168.0.100:49154/RenderingControl/ctrl", device.renderingControl?.controlUrl)
        assertEquals("http://192.168.0.100:49154/ConnectionManager/ctrl", device.connectionManager?.controlUrl)
        assertEquals("http://192.168.0.100:49154/Icons/120x120.png", device.iconUrl)
    }
}
