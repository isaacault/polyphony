package app.polyphony.core.playback.stream

import app.polyphony.core.domain.transcode.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeFallbackRegistryTest {
    @Test
    fun `marking a codec broken affects that server only and reports once`() {
        val r = TranscodeFallbackRegistry()
        assertTrue(r.markBroken("s1", AudioCodec.OPUS, "t1"))
        assertFalse(r.markBroken("s1", AudioCodec.OPUS, "t2"))
        assertTrue(r.isBroken("s1", AudioCodec.OPUS))
        assertFalse(r.isBroken("s2", AudioCodec.OPUS))
        assertFalse(r.isBroken("s1", AudioCodec.MP3))
        assertTrue(r.forcesPassthrough("t1"))
        assertEquals("s1|OPUS", r.summary)
    }

    @Test
    fun `clear resets everything`() {
        val r = TranscodeFallbackRegistry()
        r.markBroken("s1", AudioCodec.AAC, "t1")
        r.clear()
        assertFalse(r.isBroken("s1", AudioCodec.AAC))
        assertEquals("none", r.summary)
    }
}
