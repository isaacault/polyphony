package app.polyphony.core.downloads.storage

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPathsTest {

    private val flac = Track(id = "t1", serverId = "s1", title = "x", container = "flac", mimeType = "audio/flac")

    @Test
    fun `original keeps the source container`() {
        assertEquals("flac", DownloadPaths.extensionFor(flac, TranscodeProfile.ORIGINAL, deliveredMimeType = null))
    }

    @Test
    fun `original without container falls back to mime`() {
        val t = flac.copy(container = null, mimeType = "audio/mpeg")
        assertEquals("mp3", DownloadPaths.extensionFor(t, TranscodeProfile.ORIGINAL, deliveredMimeType = null))
    }

    @Test
    fun `transcoded prefers the delivered mime, then the codec default`() {
        assertEquals("ogg", DownloadPaths.extensionFor(flac, TranscodeProfile.BALANCED, deliveredMimeType = "audio/ogg; codecs=opus"))
        assertEquals("ogg", DownloadPaths.extensionFor(flac, TranscodeProfile.BALANCED, deliveredMimeType = null))
        assertEquals("mp3", DownloadPaths.extensionFor(flac, TranscodeProfile.CAST_SAFE, deliveredMimeType = null))
        val aac = TranscodeProfile("c", "AAC", AudioCodec.AAC, maxBitrateKbps = 192)
        assertEquals("m4a", DownloadPaths.extensionFor(flac, aac, deliveredMimeType = "audio/mp4"))
    }

    @Test
    fun `ids are made filesystem safe and remain unique`() {
        assertEquals("abc-123", DownloadPaths.sanitize("abc-123"))
        val weird = DownloadPaths.sanitize("al/..\\x?y")
        assertTrue(weird.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(weird != DownloadPaths.sanitize("al/..\\x!y"))
        assertEquals("s1/t1.flac", DownloadPaths.relativePath("s1", "t1", "flac"))
    }
}
