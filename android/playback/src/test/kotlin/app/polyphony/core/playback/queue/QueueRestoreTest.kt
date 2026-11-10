package app.polyphony.core.playback.queue

import androidx.media3.common.Player
import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.domain.model.Track
import app.polyphony.core.playback.util.PlaybackJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRestoreTest {
    private fun track(id: String, server: String = "s1", durationMs: Long = 200_000) =
        Track(id = id, serverId = server, title = id, durationMs = durationMs)

    private fun saved(
        ids: List<String>,
        index: Int = 0,
        positionMs: Long = 0,
        queued: List<Int> = emptyList(),
        order: List<Int> = emptyList(),
        repeatMode: Int = Player.REPEAT_MODE_OFF,
    ) = PersistedQueue(
        tracks = ids.map { track(it) },
        index = index,
        positionMs = positionMs,
        shuffle = order.isNotEmpty(),
        repeatMode = repeatMode,
        queueTitle = "Mix",
        context = PlayContext(PlayContext.Kind.PLAYLIST, "p1", "s1", "Mix"),
        queuedIndices = queued,
        shuffleOrder = order,
    )

    @Test fun `comes back at the same song and position, with its modes`() {
        val plan = QueueRestore.plan(saved(listOf("a", "b", "c"), index = 1, positionMs = 61_000, order = listOf(2, 0, 1), repeatMode = Player.REPEAT_MODE_ONE))!!
        assertEquals(listOf("a", "b", "c"), plan.tracks.map { it.id })
        assertEquals(1, plan.index)
        assertEquals(61_000, plan.positionMs)
        assertTrue(plan.shuffle)
        assertEquals(listOf(2, 0, 1), plan.shuffleOrder)
        assertEquals(Player.REPEAT_MODE_ONE, plan.repeatMode)
        assertEquals("Mix", plan.queueTitle)
        assertEquals("p1", plan.context?.id)
    }

    @Test fun `songs you queued are still Next in queue`() {
        val plan = QueueRestore.plan(saved(listOf("a", "q1", "q2", "b"), queued = listOf(1, 2)))!!
        assertEquals(listOf(false, true, true, false), plan.queued)
        assertEquals(2, UserQueue.waiting(plan.queued, plan.index))
    }

    @Test fun `out-of-range values from a damaged file are tamed`() {
        val plan = QueueRestore.plan(saved(listOf("a", "b"), index = 9, positionMs = -3, queued = listOf(7, -1, 1), order = listOf(0, 0), repeatMode = 42))!!
        assertEquals(1, plan.index)
        assertEquals(0, plan.positionMs)
        assertEquals(listOf(false, true), plan.queued)
        assertNull(plan.shuffleOrder)
        assertEquals(Player.REPEAT_MODE_OFF, plan.repeatMode)
    }

    @Test fun `songs from a server removed since are left out, the rest renumbered`() {
        val saved = PersistedQueue(
            tracks = listOf(track("a"), track("gone1", server = "old"), track("q", server = "s1"), track("b")),
            index = 3,
            positionMs = 30_000,
            queuedIndices = listOf(2),
            shuffleOrder = listOf(1, 3, 0, 2),
        )
        val plan = QueueRestore.plan(saved, knownServers = setOf("s1"))!!
        assertEquals(listOf("a", "q", "b"), plan.tracks.map { it.id })
        assertEquals("b", plan.tracks[plan.index].id)
        assertEquals(30_000, plan.positionMs)
        assertEquals(listOf(false, true, false), plan.queued)
        assertEquals(listOf(2, 0, 1), plan.shuffleOrder)
    }

    @Test fun `the playing song's server is gone, the next song takes its place from its start`() {
        val saved = PersistedQueue(tracks = listOf(track("a"), track("x", server = "old"), track("b")), index = 1, positionMs = 30_000)
        val plan = QueueRestore.plan(saved, knownServers = setOf("s1"))!!
        assertEquals("b", plan.tracks[plan.index].id)
        assertEquals(0, plan.positionMs)
    }

    @Test fun `every server gone, nothing comes back`() {
        assertNull(QueueRestore.plan(saved(listOf("a", "b")), knownServers = emptySet()))
    }

    @Test fun `servers that can't be read keep every song`() {
        assertEquals(2, QueueRestore.plan(saved(listOf("a", "b")), knownServers = null)!!.tracks.size)
    }

    @Test fun `a song saved at its very end comes back from its start`() {
        assertEquals(0, QueueRestore.plan(saved(listOf("a"), positionMs = 200_000))!!.positionMs)
        assertEquals(0, QueueRestore.plan(saved(listOf("a"), positionMs = 199_000))!!.positionMs)
        assertEquals(197_000, QueueRestore.plan(saved(listOf("a"), positionMs = 197_000))!!.positionMs)
    }

    @Test fun `resume position`() {
        assertEquals(0, QueueRestore.resumePosition(-1, 100_000))
        assertEquals(50_000, QueueRestore.resumePosition(50_000, 100_000))
        assertEquals(0, QueueRestore.resumePosition(99_000, 100_000))
        assertEquals(0, QueueRestore.resumePosition(150_000, 100_000))
        assertEquals("unknown length: where it was", 150_000, QueueRestore.resumePosition(150_000, 0))
    }

    @Test fun `an empty saved queue restores nothing`() {
        assertNull(QueueRestore.plan(saved(emptyList())))
    }

    @Test fun `what a snapshot saves comes back through the file format`() {
        val items = listOf(QueueSnapshot.Item(track("a")), QueueSnapshot.Item(track("q"), queued = true), QueueSnapshot.Item(track("b")))
        val snapshot = QueueSnapshot.of(items, 0, 5_000, listOf(1, 2, 0), shuffle = true, repeatMode = Player.REPEAT_MODE_ALL, queueTitle = "T", context = null, savedAt = 9)!!
        val decoded = PlaybackJson.decodeFromString(PersistedQueue.serializer(), PlaybackJson.encodeToString(snapshot))
        val plan = QueueRestore.plan(decoded)!!
        assertEquals(listOf(false, true, false), plan.queued)
        assertEquals(listOf(1, 2, 0), plan.shuffleOrder)
        assertEquals(5_000, plan.positionMs)
        assertEquals(Player.REPEAT_MODE_ALL, plan.repeatMode)
    }

    @Test fun `a file from before queued songs and shuffle order were saved still restores`() {
        val old = """{"tracks":[{"id":"a","serverId":"s1","title":"a"},{"id":"b","serverId":"s1","title":"b"}],"index":1,"positionMs":7700,"shuffle":true,"repeatMode":2}"""
        val plan = QueueRestore.plan(PlaybackJson.decodeFromString(PersistedQueue.serializer(), old))!!
        assertEquals(1, plan.index)
        assertEquals(7_700, plan.positionMs)
        assertEquals(listOf(false, false), plan.queued)
        assertNull("no saved order: the player shuffles afresh", plan.shuffleOrder)
        assertTrue(plan.shuffle)
    }
}

class PendingShuffleOrderTest {
    private val pending = PendingShuffleOrder(listOf("a", "b", "c"), listOf(2, 0, 1))

    @Test fun `the restored list shuffled afresh gets its saved order back`() {
        assertEquals(PendingShuffleOrder.Step.APPLY, pending.step(listOf("a", "b", "c"), listOf(0, 1, 2)))
    }

    @Test fun `in its saved order already, or no list yet, nothing to do`() {
        assertEquals(PendingShuffleOrder.Step.NOTHING, pending.step(listOf("a", "b", "c"), listOf(2, 0, 1)))
        assertEquals(PendingShuffleOrder.Step.NOTHING, pending.step(emptyList(), emptyList()))
    }

    @Test fun `another list, the order is forgotten`() {
        assertEquals(PendingShuffleOrder.Step.FORGET, pending.step(listOf("a", "b"), listOf(0, 1)))
        assertEquals(PendingShuffleOrder.Step.FORGET, pending.step(listOf("b", "a", "c"), listOf(2, 0, 1)))
    }
}

class RestoredShuffleOrderTest {
    @Test fun `a restored order plays as saved`() {
        val order = QueueShuffleOrder.restored(listOf(3, 1, 0, 2), seed = 1)
        assertEquals(listOf(3, 1, 0, 2), QueueShuffleOrder.playOrder(order))
        assertEquals(4, order.length)
    }

    @Test fun `songs queued into a restored order still go right after the one before them`() {
        val order = QueueShuffleOrder.restored(listOf(3, 1, 0, 2), seed = 1)
        // Play next while list item 1 plays: inserted at list index 2.
        assertEquals(listOf(4, 1, 2, 0, 3), QueueShuffleOrder.playOrder(order.cloneAndInsert(2, 1)))
    }

    @Test fun `a broken order is refused`() {
        assertFalse(runCatching { QueueShuffleOrder.restored(listOf(0, 0)) }.isSuccess)
    }
}
