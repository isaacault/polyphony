package app.polyphony.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `continuo's catalogue artwork asks for the small size`() {
        assertEquals("https://music-api.example/v1/artwork?src=x&size=320", reduced("https://music-api.example/v1/artwork?src=x"))
        assertEquals("https://music-api.example/v1/artwork?src=x&size=320", reduced("https://music-api.example/v1/artwork?src=x&size=1200"))
    }
}
