package app.polyphony.core.playback.transcode

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CodecSupportTest {
    private fun server(codecs: String?) = Server(
        id = "s", type = ServerType.SUBSONIC, name = "n", baseUrl = "http://x", username = "u", credential = "c",
        deviceId = "d", options = codecs?.let { mapOf(ServerOptions.TRANSCODE_CODECS to it) } ?: emptyMap(),
    )

    @Test
    fun `unknown support leaves profiles alone`() {
        assertNull(CodecSupport.supported(server(null)))
        assertSame(TranscodeProfile.BALANCED, CodecSupport.adjust(TranscodeProfile.BALANCED, null))
    }

    @Test
    fun `supported codec is kept`() {
        val s = CodecSupport.supported(server("OPUS,MP3"))
        assertSame(TranscodeProfile.BALANCED, CodecSupport.adjust(TranscodeProfile.BALANCED, s))
    }

    @Test
    fun `unsupported opus falls back to mp3 at the same bitrate`() {
        val adjusted = CodecSupport.adjust(TranscodeProfile.BALANCED, setOf(AudioCodec.MP3))
        assertEquals(AudioCodec.MP3, adjusted.codec)
        assertEquals(128, adjusted.maxBitrateKbps)
        assertEquals("auto.mp3.128", adjusted.id)
    }

    @Test
    fun `nothing lossy available means original quality`() {
        assertSame(TranscodeProfile.ORIGINAL, CodecSupport.adjust(TranscodeProfile.HIGH, emptySet()))
    }

    @Test
    fun `passthrough and flac-cap profiles are never substituted`() {
        assertSame(TranscodeProfile.ORIGINAL, CodecSupport.adjust(TranscodeProfile.ORIGINAL, emptySet()))
        val flac = TranscodeProfile.LOSSLESS_CAPPED
        assertEquals(AudioCodec.MP3, CodecSupport.adjust(flac, setOf(AudioCodec.MP3)).codec)
    }

    @Test
    fun `encode and parse round trip ignores junk`() {
        assertEquals(setOf(AudioCodec.MP3, AudioCodec.OPUS), CodecSupport.parse("OPUS, MP3,bogus"))
        assertEquals("OPUS,MP3", CodecSupport.encode(setOf(AudioCodec.MP3, AudioCodec.OPUS)))
    }
}
