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

    @Test fun `an adaptive stream falls back only when continuo answered and couldn't serve it`() {
        assertTrue("encode failed", LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 502))
        assertTrue("token refused even renewed", LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 401))
        assertTrue("playlist didn't parse", LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, null))
        assertTrue("segment didn't parse", LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        // A tunnel: the ordinary stream can't get through either, and would be heavier when it did.
        assertFalse(LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertFalse(LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertFalse(LoadFailures.continuoCouldNotServe(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null))
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
