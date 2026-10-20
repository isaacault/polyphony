package app.polyphony.core.playback.transcode

import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.playback.transcode.QualityChangeRule.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityChangeRuleTest {
    private val lossless = TranscodeProfile(id = "lossless", name = "Original")
    private val mp3 = TranscodeProfile(id = "castsafe", name = "Cast compatible", codec = AudioCodec.MP3, maxBitrateKbps = 320)
    private fun applied(profile: TranscodeProfile, target: String = "dlna:yamaha", kind: NetworkKind = NetworkKind.WIFI, metered: Boolean = false, capped: Boolean = false) =
        AppliedQuality(target, profile, kind, metered, capped)

    @Test
    fun `a device setting changed by hand reloads the playing track`() {
        assertEquals(Action.RELOAD_NOW, QualityChangeRule.decide(applied(mp3), applied(lossless), reloadOnEnvironmentChange = false))
    }

    @Test
    fun `an edited profile with the same id counts as a change`() {
        val edited = mp3.copy(maxBitrateKbps = 192)
        assertEquals(Action.RELOAD_NOW, QualityChangeRule.decide(applied(mp3), applied(edited), reloadOnEnvironmentChange = false))
    }

    @Test
    fun `a network hand-over waits for the next track by default`() {
        val before = applied(lossless, kind = NetworkKind.WIFI)
        val after = applied(mp3, kind = NetworkKind.CELLULAR, metered = true)
        assertEquals(Action.NEXT_TRACK, QualityChangeRule.decide(before, after, reloadOnEnvironmentChange = false))
        assertEquals(Action.RELOAD_NOW, QualityChangeRule.decide(before, after, reloadOnEnvironmentChange = true))
    }

    @Test
    fun `a latency cap waits for the next track by default`() {
        assertEquals(Action.NEXT_TRACK, QualityChangeRule.decide(applied(lossless), applied(mp3, capped = true), reloadOnEnvironmentChange = false))
    }

    @Test
    fun `same profile or a different output does nothing`() {
        assertEquals(Action.NONE, QualityChangeRule.decide(applied(mp3), applied(mp3), reloadOnEnvironmentChange = true))
        assertEquals(Action.NONE, QualityChangeRule.decide(applied(mp3, target = "local"), applied(lossless, target = "dlna:yamaha"), reloadOnEnvironmentChange = true))
        assertEquals(Action.NONE, QualityChangeRule.decide(null, applied(lossless), reloadOnEnvironmentChange = true))
    }
}
