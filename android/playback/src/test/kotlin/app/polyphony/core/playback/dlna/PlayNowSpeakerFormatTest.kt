package app.polyphony.core.playback.dlna

import app.polyphony.core.dlna.service.RendererFormatSupport
import app.polyphony.core.playback.dlna.PlayNowSpeakerFormat.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayNowSpeakerFormatTest {

    /** The Yamaha RX-V483's GetProtocolInfo sinks (2026-11-13), less its L16 rates: no Ogg, no Opus. */
    private val yamaha = RendererFormatSupport.from(setOf(
        "audio/3gpp", "audio/aac", "audio/aacp", "audio/aiff", "audio/dsd", "audio/flac", "audio/l16",
        "audio/m4a", "audio/mp3", "audio/mp4", "audio/mpeg", "audio/vnd.dlna.adts", "audio/wav",
        "audio/x-aac", "audio/x-aiff", "audio/x-dff", "audio/x-dsd", "audio/x-dsf", "audio/x-flac",
        "audio/x-mp3", "audio/x-mp4", "audio/x-mpeg", "audio/x-mpg", "audio/x-ms-wma", "audio/x-wav",
    ))

    @Test
    fun `an MP3 copy is labelled MP3, not FLAC`() {
        // Letter Blue's "Clementine" went to the Yamaha as audio/flac and it stopped at the hand-off.
        assertEquals(Choice("audio/mpeg", null), PlayNowSpeakerFormat.choose("mp3", yamaha))
        assertEquals(Choice("audio/flac", null), PlayNowSpeakerFormat.choose("FLAC", yamaha))
        assertEquals(Choice("audio/mp4", null), PlayNowSpeakerFormat.choose("m4a", yamaha))
    }

    @Test
    fun `an Opus copy is converted to FLAC for a speaker without Opus`() {
        assertEquals(Choice("audio/flac", "flac"), PlayNowSpeakerFormat.choose("opus", yamaha))
        assertEquals(Choice("audio/flac", "flac"), PlayNowSpeakerFormat.choose("ogg", yamaha))
    }

    @Test
    fun `a speaker that lists Opus gets the copy as it is`() {
        val opus = RendererFormatSupport.from(setOf("audio/mpeg", "audio/opus", "audio/flac"))
        assertEquals(Choice("audio/ogg", null), PlayNowSpeakerFormat.choose("opus", opus))
    }

    @Test
    fun `an MP3-only speaker gets MP3 conversions`() {
        val mp3 = RendererFormatSupport.from(setOf("audio/mpeg"))
        assertEquals(Choice("audio/mpeg", "mp3"), PlayNowSpeakerFormat.choose("flac", mp3))
        assertEquals(Choice("audio/mpeg", null), PlayNowSpeakerFormat.choose("mp3", mp3))
    }

    @Test
    fun `an unknown format is converted so the label is true`() {
        assertEquals(Choice("audio/flac", "flac"), PlayNowSpeakerFormat.choose(null, yamaha))
        assertEquals(Choice("audio/flac", "flac"), PlayNowSpeakerFormat.choose("wma", yamaha))
    }

    @Test
    fun `a speaker that never said what it plays gets the file as it is`() {
        assertEquals(Choice("audio/ogg", null), PlayNowSpeakerFormat.choose("opus", RendererFormatSupport.UNKNOWN))
    }

    @Test
    fun `the conversion goes on the signed link`() {
        val signed = "http://192.168.0.101/v1/play-now/abc/audio?d=dev&exp=1&sig=00"
        assertEquals("$signed&as=flac", PlayNowSpeakerFormat.link(signed, Choice("audio/flac", "flac")))
        assertEquals(signed, PlayNowSpeakerFormat.link(signed, Choice("audio/mpeg", null)))
        assertEquals("http://x/a?as=mp3", PlayNowSpeakerFormat.link("http://x/a", Choice("audio/mpeg", "mp3")))
    }
}
