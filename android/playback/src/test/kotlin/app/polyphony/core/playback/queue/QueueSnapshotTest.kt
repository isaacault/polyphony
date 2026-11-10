package app.polyphony.core.playback.queue

import androidx.media3.common.Player
import app.polyphony.core.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotTest {
    private fun track(id: String) = Track(id = id, serverId = "s1", title = id, durationMs = 200_000)
    private fun item(id: String?, queued: Boolean = false) = QueueSnapshot.Item(id?.let(::track), queued)

    private fun snapshot(
        items: List<QueueSnapshot.Item>,
        current: Int,
        positionMs: Long = 42_000,
        order: List<Int>? = null,
    ) = QueueSnapshot.of(items, current, positionMs, order, shuffle = order != null, repeatMode = Player.REPEAT_MODE_ALL, queueTitle = "Album", context = null, savedAt = 1L)

    @Test fun `saves the list, the place, the songs you queued and the shuffle order`() {
        val saved = snapshot(listOf(item("a"), item("b"), item("q", queued = true), item("c")), current = 1, order = listOf(2, 1, 3, 0))!!
        assertEquals(listOf("a", "b", "q", "c"), saved.tracks.map { it.id })
        assertEquals(1, saved.index)
        assertEquals(42_000, saved.positionMs)
        assertEquals(listOf(2), saved.queuedIndices)
        assertEquals(listOf(2, 1, 3, 0), saved.shuffleOrder)
        assertTrue(saved.shuffle)
        assertEquals(Player.REPEAT_MODE_ALL, saved.repeatMode)
    }

    @Test fun `an unknown item before the current one doesn't shift the saved place`() {
        // Used to save index 2 of the kept list: "c" instead of "b".
        val saved = snapshot(listOf(item("a"), item(null), item("b"), item("c")), current = 2)!!
        assertEquals(listOf("a", "b", "c"), saved.tracks.map { it.id })
        assertEquals("b", saved.tracks[saved.index].id)
        assertEquals(42_000, saved.positionMs)
    }

    @Test fun `an unknown current item gives its place to the next song, from the start`() {
        val saved = snapshot(listOf(item("a"), item(null), item("b")), current = 1)!!
        assertEquals("b", saved.tracks[saved.index].id)
        assertEquals(0, saved.positionMs)
    }

    @Test fun `an unknown last item falls back to the last known song`() {
        val saved = snapshot(listOf(item("a"), item("b"), item(null)), current = 2)!!
        assertEquals("b", saved.tracks[saved.index].id)
        assertEquals(0, saved.positionMs)
    }

    @Test fun `queued flags and the shuffle order are renumbered around a gap`() {
        val saved = snapshot(
            listOf(item("a"), item(null), item("q", queued = true), item("b")),
            current = 0,
            order = listOf(3, 1, 0, 2),
        )!!
        assertEquals(listOf(1), saved.queuedIndices)
        assertEquals(listOf(2, 0, 1), saved.shuffleOrder)
    }

    @Test fun `a broken shuffle order isn't saved`() {
        assertEquals(emptyList<Int>(), snapshot(listOf(item("a"), item("b")), 0, order = listOf(0, 0))!!.shuffleOrder)
        assertEquals(emptyList<Int>(), snapshot(listOf(item("a"), item("b")), 0, order = listOf(0))!!.shuffleOrder)
    }

    @Test fun `no position before zero, and no index for an empty player`() {
        val saved = snapshot(listOf(item("a")), current = -1, positionMs = -5)!!
        assertEquals(0, saved.index)
        assertEquals(0, saved.positionMs)
    }

    @Test fun `nothing to save when no track is known`() {
        assertNull(snapshot(emptyList(), current = -1))
        assertNull(snapshot(listOf(item(null)), current = 0))
    }

    @Test fun `permutation check`() {
        assertTrue(QueueSnapshot.isPermutation(listOf(2, 0, 1), 3))
        assertTrue(QueueSnapshot.isPermutation(emptyList(), 0))
        assertFalse(QueueSnapshot.isPermutation(listOf(0, 1, 3), 3))
        assertFalse(QueueSnapshot.isPermutation(listOf(0, 1, 1), 3))
        assertFalse(QueueSnapshot.isPermutation(listOf(0, 1), 3))
    }
}
