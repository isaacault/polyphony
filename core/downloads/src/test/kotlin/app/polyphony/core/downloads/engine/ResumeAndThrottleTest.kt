package app.polyphony.core.downloads.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeAndThrottleTest {

    @Test
    fun `original downloads resume from the partial file`() {
        assertEquals(ResumeDecision.RangeFrom(4096), ResumePolicy.decide(isOriginal = true, partExists = true, partSizeBytes = 4096))
    }

    @Test
    fun `transcoded downloads always restart`() {
        assertEquals(ResumeDecision.Restart, ResumePolicy.decide(isOriginal = false, partExists = true, partSizeBytes = 4096))
    }

    @Test
    fun `missing or empty partial files restart`() {
        assertEquals(ResumeDecision.Restart, ResumePolicy.decide(isOriginal = true, partExists = false, partSizeBytes = 0))
        assertEquals(ResumeDecision.Restart, ResumePolicy.decide(isOriginal = true, partExists = true, partSizeBytes = 0))
    }

    @Test
    fun `throttle limits emissions to the interval but always passes completion`() {
        var now = 0L
        val throttle = ProgressThrottle(minIntervalMs = 500) { now }
        assertTrue(throttle.shouldEmit(0.1f))
        now = 100
        assertFalse(throttle.shouldEmit(0.2f))
        now = 600
        assertTrue(throttle.shouldEmit(0.3f))
        now = 650
        assertTrue(throttle.shouldEmit(1f))
        now = 660
        assertTrue(throttle.shouldEmit(0.5f, force = true))
    }

    @Test
    fun `throttle skips unchanged values`() {
        var now = 0L
        val throttle = ProgressThrottle(minIntervalMs = 100) { now }
        assertTrue(throttle.shouldEmit(0.4f))
        now = 1000
        assertFalse(throttle.shouldEmit(0.4f))
    }
}
