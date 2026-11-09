package app.polyphony.core.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputVolumeTest {
    @Test fun `the player gets every factor at once`() {
        var applied = -1f
        val v = OutputVolume { applied = it }
        v.gain = 0.5f
        v.sleep = 0.5f
        assertEquals(0.25f, applied, 1e-6f)
        v.fade = 0f
        assertEquals(0f, applied, 1e-6f)
        v.fade = 1f
        assertEquals(0.25f, applied, 1e-6f)
    }

    @Test fun `the sleep timer restoring full volume keeps ReplayGain's level`() {
        var applied = -1f
        val v = OutputVolume { applied = it }
        v.gain = 0.6f
        v.sleep = 0.2f
        v.sleep = 1f
        assertEquals(0.6f, applied, 1e-6f)
    }

    @Test fun `factors stay between nothing and full`() {
        var applied = -1f
        val v = OutputVolume { applied = it }
        v.gain = 3f
        v.fade = -1f
        assertEquals(0f, applied, 1e-6f)
        v.fade = 1f
        assertEquals(1f, applied, 1e-6f)
    }
}
