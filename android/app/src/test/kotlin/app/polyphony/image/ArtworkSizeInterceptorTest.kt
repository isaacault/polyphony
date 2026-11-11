package app.polyphony.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkSizeInterceptorTest {
    private fun reduced(url: String) = ArtworkSizeInterceptor.reducedUrl(url, 320)

    @Test
    fun `caps a larger cover size`() {
        assertEquals(
            "http://host/rest/getCoverArt?id=al-1&size=320&u=me",
            reduced("http://host/rest/getCoverArt?id=al-1&size=600&u=me"),
        )
    }

    @Test
    fun `leaves an already-small cover alone`() {
        assertNull(reduced("http://host/rest/getCoverArt?id=al-1&size=200"))
        assertNull(reduced("http://host/rest/getCoverArt?id=al-1&size=320"))
    }

    @Test
    fun `ignores non-cover and sizeless urls`() {
        assertNull(reduced("http://host/rest/stream?id=tr-1&size=600"))
        assertNull(reduced("http://host/rest/getCoverArt?id=al-1"))
    }

    @Test
    fun `jellyfin covers are capped too`() {
        assertEquals(
            "http://jf:8096/Items/abc/Images/Primary?maxWidth=320&maxHeight=320&quality=90",
            reduced("http://jf:8096/Items/abc/Images/Primary?maxWidth=600&maxHeight=600&quality=90"),
        )
        assertNull(reduced("http://jf:8096/Items/abc/Images/Primary?maxWidth=200&maxHeight=200&quality=90"))
    }

    @Test
    fun `only when the setting is on, on mobile data or while streaming stalls`() {
        assertTrue(DataSaver.shouldReduce(enabled = true, metered = true, stalling = false))
        assertTrue("slow Wi-Fi", DataSaver.shouldReduce(enabled = true, metered = false, stalling = true))
        assertFalse(DataSaver.shouldReduce(enabled = true, metered = false, stalling = false))
        assertFalse(DataSaver.shouldReduce(enabled = false, metered = true, stalling = true))
    }

    @Test
    fun `continuo's catalogue artwork asks for the small size`() {
        assertEquals("https://music-api.example/v1/artwork?src=x&size=320", reduced("https://music-api.example/v1/artwork?src=x"))
        assertEquals("https://music-api.example/v1/artwork?src=x&size=320", reduced("https://music-api.example/v1/artwork?src=x&size=1200"))
    }
}
