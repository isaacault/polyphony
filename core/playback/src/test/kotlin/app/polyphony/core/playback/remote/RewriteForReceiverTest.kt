package app.polyphony.core.playback.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteForReceiverTest {
    private val stream = "https://music.example.com/rest/stream?u=me&t=abc&id=42"

    @Test
    fun `explicit override replaces the base url and keeps the path and query`() {
        assertEquals(
            "http://192.168.1.10:4533/rest/stream?u=me&t=abc&id=42",
            rewriteForReceiver(stream, "https://music.example.com", "http://192.168.1.10:4533/", null),
        )
    }

    @Test
    fun `override without scheme defaults to http`() {
        assertEquals(
            "http://192.168.1.10:4533/rest/stream?u=me&t=abc&id=42",
            rewriteForReceiver(stream, "https://music.example.com", "192.168.1.10:4533", null),
        )
    }

    @Test
    fun `override is ignored for urls from another origin`() {
        val other = "https://cdn.example.net/art.jpg"
        assertEquals(other, rewriteForReceiver(other, "https://music.example.com", "http://192.168.1.10:4533", null))
    }

    @Test
    fun `lan ip is substituted for plain http hosts`() {
        assertEquals(
            "http://192.168.1.10:4533/rest/stream?id=42",
            rewriteForReceiver("http://music.example.com:4533/rest/stream?id=42", "http://music.example.com:4533", null, "192.168.1.10"),
        )
    }

    @Test
    fun `lan ip is never substituted under https`() {
        assertEquals(stream, rewriteForReceiver(stream, "https://music.example.com", null, "192.168.1.10"))
    }

    @Test
    fun `ip literal hosts and missing lan ip are left alone`() {
        val ip = "http://10.0.0.5:4533/rest/stream?id=1"
        assertEquals(ip, rewriteForReceiver(ip, "http://10.0.0.5:4533", null, "192.168.1.10"))
        assertEquals("http://music.example.com/x", rewriteForReceiver("http://music.example.com/x", "http://music.example.com", null, null))
    }
}
