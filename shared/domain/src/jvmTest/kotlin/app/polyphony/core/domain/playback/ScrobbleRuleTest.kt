package app.polyphony.core.domain.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as PolyphonyKit's PlaybackTests (ScrobbleRule), so the two apps count alike. */
class ScrobbleRuleTest {
    @Test
    fun `half the song`() {
        assertFalse(ScrobbleRule.counts(99_000, 200_000))
        assertTrue(ScrobbleRule.counts(100_000, 200_000))
    }

    @Test
    fun `four minutes into a long mix`() {
        assertTrue(ScrobbleRule.counts(240_000, 3_600_000))
    }

    @Test
    fun `never under ten seconds`() {
        assertFalse(ScrobbleRule.counts(9_000, 16_000))
        assertTrue(ScrobbleRule.counts(10_000, 16_000))
    }

    @Test
    fun `four minutes when the length is unknown`() {
        assertFalse(ScrobbleRule.counts(200_000, 0))
        assertTrue(ScrobbleRule.counts(240_000, 0))
    }

    @Test
    fun `another threshold`() {
        assertTrue(ScrobbleRule.counts(60_000, 200_000, threshold = 0.3f))
        assertFalse(ScrobbleRule.counts(59_000, 200_000, threshold = 0.3f))
    }
}
