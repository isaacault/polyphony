package app.polyphony.core.playback.queue

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueShuffleOrderTest {
    private fun walk(order: androidx.media3.exoplayer.source.ShuffleOrder): List<Int> {
        val out = ArrayList<Int>()
        var i = order.firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = order.getNextIndex(i) }
        return out
    }

    @Test fun `a fresh list is shuffled, every index once`() {
        val o = QueueShuffleOrder(seed = 1).cloneAndInsert(0, 10)
        assertEquals((0 until 10).toList(), walk(o).sorted())
    }

    @Test fun `queued songs go straight after the song before them in the shuffled order`() {
        val o = QueueShuffleOrder(seed = 7).cloneAndInsert(0, 5) as QueueShuffleOrder
        val before = o.order()
        val current = before[2]                          // playing the third shuffled song
        // Play next: inserted at list index current+1, so after `current` in shuffle order too.
        val next = o.cloneAndInsert(current + 1, 1)
        val played = walk(next)
        assertEquals("Play next: straight after the current", current + 1, played[played.indexOf(current) + 1])
        // Add to queue behind it: at list index current+2, straight after the one queued.
        val both = walk(next.cloneAndInsert(current + 2, 1))
        val at = both.indexOf(current)
        assertEquals(listOf(current + 1, current + 2), both.subList(at + 1, at + 3))
    }

    @Test fun `removing keeps the rest in order and renumbers`() {
        val o = QueueShuffleOrder(seed = 3).cloneAndInsert(0, 4) as QueueShuffleOrder
        val removed = o.cloneAndRemove(1, 2) as QueueShuffleOrder
        assertEquals(o.order().filter { it != 1 }.map { if (it > 1) it - 1 else it }, removed.order())
    }
}
