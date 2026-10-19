package app.polyphony.core.playback.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueMathTest {
    @Test
    fun `insert after current`() {
        assertEquals(0, QueueMath.insertAfterCurrentIndex(0, -1))
        assertEquals(3, QueueMath.insertAfterCurrentIndex(3, -1))
        assertEquals(1, QueueMath.insertAfterCurrentIndex(5, 0))
        assertEquals(5, QueueMath.insertAfterCurrentIndex(5, 4))
    }

    @Test
    fun `shuffled start index within bounds`() {
        assertEquals(0, QueueMath.shuffledStartIndex(0))
        assertEquals(0, QueueMath.shuffledStartIndex(1))
        repeat(50) { assertTrue(QueueMath.shuffledStartIndex(7, Random(it)) in 0 until 7) }
    }

    @Test
    fun `current index after move`() {
        assertEquals(4, QueueMath.currentIndexAfterMove(current = 2, from = 2, to = 4))
        assertEquals(1, QueueMath.currentIndexAfterMove(current = 2, from = 0, to = 3))
        assertEquals(3, QueueMath.currentIndexAfterMove(current = 2, from = 5, to = 1))
        assertEquals(2, QueueMath.currentIndexAfterMove(current = 2, from = 4, to = 5))
    }

    @Test
    fun `current index after remove`() {
        assertEquals(-1, QueueMath.currentIndexAfterRemove(0, 0, 1))
        assertEquals(1, QueueMath.currentIndexAfterRemove(2, 0, 5))
        assertEquals(2, QueueMath.currentIndexAfterRemove(2, 2, 5))
        assertEquals(3, QueueMath.currentIndexAfterRemove(4, 4, 5))
        assertEquals(2, QueueMath.currentIndexAfterRemove(2, 4, 5))
    }
}
