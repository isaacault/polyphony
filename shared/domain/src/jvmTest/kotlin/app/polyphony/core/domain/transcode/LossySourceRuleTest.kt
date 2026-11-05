package app.polyphony.core.domain.transcode

import app.polyphony.core.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LossySourceRuleTest {
    private fun track(container: String?) = Track(id = "t", serverId = "s", title = "T", container = container)
    private val high = TranscodeDecision(TranscodeProfile.HIGH, TranscodeDecision.Reason.CELLULAR_RULE)

    @Test
    fun `an mp3 is streamed as it is instead of re-encoded to opus`() {
        val d = LossySourceRule.apply(high, track("mp3"))
        assertEquals(TranscodeProfile.ORIGINAL, d.profile)
        assertEquals(TranscodeDecision.Reason.LOSSY_SOURCE, d.reason)
    }

    @Test
    fun `a struggling connection still gets the smaller stream`() {
        val capped = TranscodeDecision(TranscodeProfile.BALANCED, TranscodeDecision.Reason.CELLULAR_RULE, cappedByLatency = true)
        assertSame(capped, LossySourceRule.apply(capped, track("mp3")))
    }

    @Test
    fun `lossless sources are transcoded as the profile says`() {
        assertSame(high, LossySourceRule.apply(high, track("flac")))
    }

    @Test
    fun `unknown or exotic lossy formats are left to the profile`() {
        assertSame(high, LossySourceRule.apply(high, track(null)))
        assertSame(high, LossySourceRule.apply(high, track("wma")))
    }

    @Test
    fun `lossless and original profiles are untouched`() {
        val original = TranscodeDecision(TranscodeProfile.ORIGINAL, TranscodeDecision.Reason.WIFI_RULE)
        assertSame(original, LossySourceRule.apply(original, track("mp3")))
        val flac = TranscodeDecision(TranscodeProfile.LOSSLESS_CAPPED, TranscodeDecision.Reason.WIFI_RULE)
        assertSame(flac, LossySourceRule.apply(flac, track("mp3")))
    }
}
