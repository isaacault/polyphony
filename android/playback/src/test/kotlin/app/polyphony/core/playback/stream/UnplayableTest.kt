package app.polyphony.core.playback.stream

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UnplayableTest {
    @Test fun `an unplayable song is recognised however deep the player wrapped it`() {
        val wrapped = RuntimeException("playback", IOException("source", UnplayableSongException("Not downloaded; offline mode is on")))
        assertTrue(LoadFailures.isUnplayable(wrapped))
        assertTrue(LoadFailures.isUnplayable(UnplayableSongException("Unknown server s1")))
    }

    @Test fun `ordinary load failures are not unplayable`() {
        assertFalse(LoadFailures.isUnplayable(RuntimeException(IOException("connection reset"))))
        assertFalse(LoadFailures.isUnplayable(null))
    }

    @Test fun `no HTTP status for a failure that wasn't one`() {
        assertNull(LoadFailures.httpStatus(IOException("timeout")))
    }

    @Test fun `only not-found answers mean the song is gone`() {
        assertTrue(LoadFailures.isGone(404))
        assertTrue(LoadFailures.isGone(410))
        assertFalse("an outage is waited out", LoadFailures.isGone(503))
        assertFalse(LoadFailures.isGone(401))
        assertFalse(LoadFailures.isGone(null))
    }

    @Test fun `an adaptive stream waits only for a lost connection, and falls back otherwise`() {
        // A tunnel: the ordinary stream can't get through either, and would be heavier when it did.
        assertTrue(LoadFailures.connectionLost(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, online = true))
        assertTrue(LoadFailures.connectionLost(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, online = true))
        assertTrue("offline, whatever the error", LoadFailures.connectionLost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, online = false))
        // Online, continuo answered and couldn't serve it.
        assertFalse("an error status", LoadFailures.connectionLost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, online = true))
        assertFalse("a wrong content type", LoadFailures.connectionLost(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, online = true))
        assertFalse("a playlist that doesn't parse", LoadFailures.connectionLost(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, online = true))
        assertFalse("an unexpected I/O error", LoadFailures.connectionLost(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, online = true))
    }

    @Test fun `skips at most once per song until something plays`() {
        val skips = UnplayableSkips()
        assertEquals(listOf(true, true, true, false), (1..4).map { skips.allow(queueSize = 3) })
        skips.onPlaying()
        assertTrue(skips.allow(queueSize = 3))
    }

    @Test fun `an empty queue never skips`() {
        assertFalse(UnplayableSkips().allow(queueSize = 0))
    }
}
