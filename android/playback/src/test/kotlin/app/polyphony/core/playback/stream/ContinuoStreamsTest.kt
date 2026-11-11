package app.polyphony.core.playback.stream

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuoStreamsTest {
    private val server = Server(
        id = "s", type = ServerType.SUBSONIC, name = "continuo", baseUrl = "https://music.example",
        username = "isaac", credential = "", deviceId = "d",
    )

    @Test fun `a token is fetched once and kept`() {
        var fetches = 0
        val streams = ContinuoStreams { _, _ -> fetches++; mapOf("Authorization" to "Bearer t") }
        repeat(3) { assertEquals("Bearer t", streams.headersFor(server)["Authorization"]) }
        assertEquals(1, fetches)
    }

    @Test fun `offline with no token, cached segments don't each wait on another fetch`() {
        var fetches = 0
        val streams = ContinuoStreams { _, _ -> fetches++; error("offline") }
        repeat(5) { assertEquals(emptyMap<String, String>(), streams.headersFor(server)) }
        assertEquals(1, fetches)
    }

    @Test fun `a refused token is always renewed`() {
        var fetches = 0
        val streams = ContinuoStreams { _, refresh -> fetches++; if (refresh) mapOf("Authorization" to "Bearer new") else emptyMap() }
        streams.headersFor(server)
        assertEquals("Bearer new", streams.headersFor(server, refresh = true)["Authorization"])
        assertEquals("Bearer new", streams.headersFor(server)["Authorization"])
        assertEquals(2, fetches)
    }
}
