package app.polyphony.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeActionMathTest {

    private val threshold = 300f

    @Test
    fun `threshold scales with width but is capped`() {
        assertEquals(300f, SwipeActionMath.thresholdPx(widthPx = 1000f, maxThresholdPx = 400f), 0.01f)
        assertEquals(400f, SwipeActionMath.thresholdPx(widthPx = 2000f, maxThresholdPx = 400f), 0.01f)
    }

    @Test
    fun `offset follows the finger up to the cap then resists`() {
        assertEquals(120f, SwipeActionMath.dampedOffset(120f, threshold), 0.01f)
        assertEquals(-450f, SwipeActionMath.dampedOffset(-450f, threshold), 0.01f)
        val past = SwipeActionMath.dampedOffset(650f, threshold)
        assertEquals(450f + 200f * SwipeActionMath.OVERSHOOT_RESISTANCE, past, 0.01f)
        assertTrue(past < 650f)
    }

    @Test
    fun `slow swipe commits only past the threshold`() {
        assertFalse(SwipeActionMath.shouldCommit(rawPx = 299f, thresholdPx = threshold, velocityPxPerS = 0f))
        assertTrue(SwipeActionMath.shouldCommit(rawPx = 300f, thresholdPx = threshold, velocityPxPerS = 0f))
        assertTrue(SwipeActionMath.shouldCommit(rawPx = -300f, thresholdPx = threshold, velocityPxPerS = 0f))
    }

    @Test
    fun `fast fling commits with less travel but not from nothing`() {
        assertTrue(SwipeActionMath.shouldCommit(rawPx = 150f, thresholdPx = threshold, velocityPxPerS = 2500f))
        assertFalse(SwipeActionMath.shouldCommit(rawPx = 50f, thresholdPx = threshold, velocityPxPerS = 2500f))
        assertFalse(SwipeActionMath.shouldCommit(rawPx = 150f, thresholdPx = threshold, velocityPxPerS = 1000f))
    }

    @Test
    fun `fling against the swipe direction never commits early`() {
        assertFalse(SwipeActionMath.shouldCommit(rawPx = 150f, thresholdPx = threshold, velocityPxPerS = -2500f))
        assertFalse(SwipeActionMath.shouldCommit(rawPx = -150f, thresholdPx = threshold, velocityPxPerS = 2500f))
    }
}
