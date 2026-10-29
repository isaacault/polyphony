package app.polyphony.core.dlna.upnp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JVM unit tests exercise the parser with the desktop XML implementation; this runs the same
 * real-world descriptions through Android's own parser, whose factory rejects settings the JVM
 * accepts. Run with `./gradlew :core:dlna:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDescriptionAndroidTest {

    private fun fixture(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().readText()

    @Test
    fun parsesYamahaRxV483OnAndroid() {
        val result = DeviceDescriptionParser.parseOrExplain(fixture("yamaha-rx-v483-desc.xml"), "http://192.168.0.100:49154/MediaRenderer/desc.xml")
        val device = result.getOrElse { throw AssertionError("Android parser rejected a valid description: ${it.message}", it) }
        assertEquals("Lounge", device.friendlyName)
        assertEquals("http://192.168.0.100:49154/AVTransport/ctrl", device.avTransport?.controlUrl)
    }
}
