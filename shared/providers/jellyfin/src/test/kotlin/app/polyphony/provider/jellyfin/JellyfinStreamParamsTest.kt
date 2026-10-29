package app.polyphony.provider.jellyfin

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinStreamParamsTest {

    private val flacHiRes = Track(
        id = "1", serverId = "s", title = "t", container = "flac", bitrateKbps = 2800, sampleRateHz = 96_000, bitDepth = 24,
    )
    private val flacCd = Track(id = "2", serverId = "s", title = "t", container = "flac", bitrateKbps = 900, sampleRateHz = 44_100, bitDepth = 16)
    private val mp3 = Track(id = "3", serverId = "s", title = "t", container = "mp3", bitrateKbps = 192, sampleRateHz = 44_100)
    private val ape = Track(id = "4", serverId = "s", title = "t", container = "ape", bitrateKbps = 800)

    @Test
    fun `original profile direct plays natively supported files and falls back to flac`() {
        val p = JellyfinStreamParams.from(TranscodeProfile.ORIGINAL, flacHiRes)
        assertEquals("flac", p.audioCodec)
        assertEquals("flac", p.transcodingContainer)
        assertNull(p.maxStreamingBitrate)
        assertNull(p.maxAudioSampleRate)
        assertTrue(p.containers.contains("flac"))
        assertTrue(p.containers.contains("mp3"))
        assertFalse(p.likelyTranscoded)
        assertEquals("audio/flac", p.mimeTypeFor(flacHiRes))
    }

    @Test
    fun `unsupported containers are transcoded even on original profile`() {
        val p = JellyfinStreamParams.from(TranscodeProfile.ORIGINAL, ape)
        assertTrue(p.likelyTranscoded)
        assertEquals("audio/flac", p.mimeTypeFor(ape))
    }

    @Test
    fun `opus profile caps bitrate and never direct plays lossless`() {
        val p = JellyfinStreamParams.from(TranscodeProfile.BALANCED, flacCd)
        assertEquals("opus", p.audioCodec)
        assertEquals("opus", p.transcodingContainer)
        assertEquals(128_000, p.maxStreamingBitrate)
        assertEquals(128_000, p.audioBitRate)
        assertFalse(p.containers.contains("flac"))
        assertTrue(p.likelyTranscoded)
        assertEquals("audio/ogg", p.mimeTypeFor(flacCd))
    }

    @Test
    fun `lossy file under the cap direct plays and over the cap transcodes`() {
        // 192 kbps MP3 is under the 256 kbps "High" cap → direct play, original MIME.
        val under = JellyfinStreamParams.from(TranscodeProfile.HIGH, mp3)
        assertFalse(under.likelyTranscoded)
        assertEquals("audio/mpeg", under.mimeTypeFor(mp3))
        assertTrue(under.containers.contains("mp3"))
        // ...but over the 128 kbps "Balanced" and 64 kbps "Data saver" caps → transcoded to Opus.
        assertTrue(JellyfinStreamParams.from(TranscodeProfile.BALANCED, mp3).likelyTranscoded)
        val saver = JellyfinStreamParams.from(TranscodeProfile.SAVER, mp3)
        assertTrue(saver.likelyTranscoded)
        assertEquals("audio/ogg", saver.mimeTypeFor(mp3))
    }

    @Test
    fun `lossless cap profile transcodes hi-res but not cd quality`() {
        val hi = JellyfinStreamParams.from(TranscodeProfile.LOSSLESS_CAPPED, flacHiRes)
        assertEquals(48_000, hi.maxAudioSampleRate)
        assertEquals(16, hi.maxAudioBitDepth)
        assertNull(hi.audioBitRate)
        assertTrue(hi.likelyTranscoded)
        val cd = JellyfinStreamParams.from(TranscodeProfile.LOSSLESS_CAPPED, flacCd)
        assertFalse(cd.likelyTranscoded)
    }

    @Test
    fun `custom codecs map to jellyfin names`() {
        val aac = JellyfinStreamParams.from(TranscodeProfile("c", "c", AudioCodec.AAC, maxBitrateKbps = 192), mp3)
        assertEquals("aac", aac.audioCodec)
        assertEquals("aac", aac.transcodingContainer)
        assertEquals("audio/aac", aac.transcodedMimeType)
        val vorbis = JellyfinStreamParams.from(TranscodeProfile("v", "v", AudioCodec.VORBIS), mp3)
        assertEquals("vorbis", vorbis.audioCodec)
        assertEquals("ogg", vorbis.transcodingContainer)
        val mp3Profile = JellyfinStreamParams.from(TranscodeProfile.CAST_SAFE, flacCd)
        assertEquals("mp3", mp3Profile.audioCodec)
        assertEquals(320_000, mp3Profile.maxStreamingBitrate)
    }
}
