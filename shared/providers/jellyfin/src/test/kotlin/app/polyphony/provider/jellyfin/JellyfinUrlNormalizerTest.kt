package app.polyphony.provider.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinUrlNormalizerTest {

    @Test
    fun `explicit scheme is kept and trailing slash removed`() {
        assertEquals("https://jf.example.com/jellyfin", JellyfinUrlNormalizer.normalize("https://jf.example.com/jellyfin/"))
        assertEquals("http://media.local:8096", JellyfinUrlNormalizer.normalize("http://media.local:8096/"))
    }

    @Test
    fun `bare ipv4 gets http and the default port`() {
        assertEquals("http://192.168.1.10:8096", JellyfinUrlNormalizer.normalize("192.168.1.10"))
        assertEquals("http://192.168.1.10:8920", JellyfinUrlNormalizer.normalize("192.168.1.10:8920"))
    }

    @Test
    fun `local host with a path does not get a default port`() {
        assertEquals("http://nas.local/jf", JellyfinUrlNormalizer.normalize("nas.local/jf/"))
        assertEquals("http://localhost:8096", JellyfinUrlNormalizer.normalize(" localhost "))
    }

    @Test
    fun `public hostnames default to https`() {
        assertEquals("https://jellyfin.example.com", JellyfinUrlNormalizer.normalize("jellyfin.example.com"))
        assertEquals("https://jellyfin.example.com:8920", JellyfinUrlNormalizer.normalize("jellyfin.example.com:8920"))
    }

    @Test
    fun `ipv6 literals are treated as local`() {
        assertEquals("http://[::1]:8096", JellyfinUrlNormalizer.normalize("[::1]"))
        assertEquals("http://[fe80::1]:8096", JellyfinUrlNormalizer.normalize("[fe80::1]:8096"))
        assertTrue(JellyfinUrlNormalizer.isLocalHost("fe80::1"))
        assertFalse(JellyfinUrlNormalizer.isLocalHost("example.org"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty input is rejected`() {
        JellyfinUrlNormalizer.normalize("   ")
    }
}
