package app.polyphony.core.playback.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeLengthTest {
    @Test
    fun `the total comes from a finished transcode's content range`() {
        // What Navidrome sends for a cached Opus transcode asked for bytes=0-0.
        assertEquals(14_537_808L, TranscodeLength.totalFromContentRange("bytes 0-0/14537808"))
        assertEquals(10L, TranscodeLength.totalFromContentRange("Bytes 4-9/10"))
    }

    @Test
    fun `no usable total means a live transcode`() {
        assertNull(TranscodeLength.totalFromContentRange(null))
        assertNull(TranscodeLength.totalFromContentRange("bytes 0-0/*"))
        assertNull(TranscodeLength.totalFromContentRange("bytes */100"))
        assertNull(TranscodeLength.totalFromContentRange("garbage"))
    }

    @Test
    fun `a length key is told apart from the live key`() {
        val key = TranscodeLength.keyWithLength("t1|opus-160", 1234)
        assertEquals("t1|opus-160|L1234", key)
        assertTrue(key.startsWith(TranscodeLength.lengthKeyPrefix("t1|opus-160")))
        assertTrue(TranscodeLength.hasKnownLength(key))
        assertFalse(TranscodeLength.hasKnownLength("t1|opus-160"))
        assertFalse(TranscodeLength.hasKnownLength(null))
    }
}
