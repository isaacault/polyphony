package app.polyphony.core.playback.dlna

import app.polyphony.core.dlna.service.RendererFormatSupport
import app.polyphony.core.domain.model.StreamRequest
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaCodecFallbackTest {

    private fun track(container: String, mime: String? = null) =
        Track(id = "t1", serverId = "s1", title = "Song", container = container, mimeType = mime)

    /** A provider stand-in: passthrough keeps the file's MIME, transcodes report the codec's MIME. */
    private val request: (TranscodeProfile) -> StreamRequest = { profile ->
        val mime = when (profile.codec) {
            AudioCodec.ORIGINAL -> null
            AudioCodec.FLAC -> "audio/flac"
            AudioCodec.MP3 -> "audio/mpeg"
            AudioCodec.AAC -> "audio/mp4"
            AudioCodec.OPUS -> "audio/ogg"
            AudioCodec.VORBIS -> "audio/ogg"
        }
        StreamRequest(url = "http://x/${profile.id}", mimeType = mime, isTranscoded = profile.codec != AudioCodec.ORIGINAL)
    }

    @Test
    fun `unknown renderer support keeps the preferred profile`() {
        val c = DlnaCodecFallback.choose(track("flac"), TranscodeProfile.HIGH, RendererFormatSupport.UNKNOWN, request)
        assertEquals(TranscodeProfile.HIGH_ID, c.profile.id)
        assertFalse(c.substituted)
    }

    @Test
    fun `opus profile on an mp3-only renderer falls back to mp3 transcode`() {
        val support = RendererFormatSupport.from(setOf("audio/mpeg"))
        val c = DlnaCodecFallback.choose(track("flac", "audio/flac"), TranscodeProfile.HIGH, support, request)
        assertTrue(c.substituted)
        assertEquals(TranscodeProfile.CAST_SAFE_ID, c.profile.id)
        assertEquals("audio/mpeg", c.mimeType)
    }

    @Test
    fun `mp3 file on an mp3-only renderer is passed through instead of transcoded`() {
        val support = RendererFormatSupport.from(setOf("audio/mpeg"))
        val c = DlnaCodecFallback.choose(track("mp3", "audio/mpeg"), TranscodeProfile.HIGH, support, request)
        assertTrue(c.substituted)
        assertEquals(TranscodeProfile.ORIGINAL_ID, c.profile.id)
    }

    @Test
    fun `flac-capable renderer prefers lossless when original is unsupported`() {
        val support = RendererFormatSupport.from(setOf("audio/flac", "audio/mpeg"))
        val c = DlnaCodecFallback.choose(track("opus", "audio/ogg"), TranscodeProfile.ORIGINAL, support, request)
        assertTrue(c.substituted)
        assertEquals(TranscodeProfile.LOSSLESS_CAPPED_ID, c.profile.id)
        assertEquals("audio/flac", c.mimeType)
    }

    @Test
    fun `supported original passes untouched`() {
        val support = RendererFormatSupport.from(setOf("audio/flac"))
        val c = DlnaCodecFallback.choose(track("flac", "audio/flac"), TranscodeProfile.ORIGINAL, support, request)
        assertFalse(c.substituted)
        assertEquals("audio/flac", c.mimeType)
    }

    @Test
    fun `mime guess from container`() {
        assertEquals("audio/mp4", DlnaCodecFallback.guessMime("m4a"))
        assertEquals("audio/ogg", DlnaCodecFallback.guessMime("opus"))
        assertEquals("audio/mpeg", DlnaCodecFallback.guessMime(null))
    }
}
