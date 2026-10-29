package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionTest {

    private fun dl(id: String, size: Long, lastAccess: Long, created: Long = lastAccess, pinned: Boolean = false) =
        DownloadedTrack(
            trackId = id, serverId = "s", status = DownloadStatus.COMPLETED, filePath = "/f/$id",
            sizeBytes = size, pinned = pinned, createdAt = created, lastAccessAt = lastAccess,
        )

    @Test
    fun `nothing is evicted under the cap or without a cap`() {
        val items = listOf(dl("a", 100, 1), dl("b", 100, 2))
        assertTrue(Eviction.plan(items, usedBytes = 200, limitBytes = 300, evictLeastRecentlyPlayed = true).isEmpty())
        assertTrue(Eviction.plan(items, usedBytes = 200, limitBytes = 0, evictLeastRecentlyPlayed = true).isEmpty())
    }

    @Test
    fun `least recently played goes first until under the cap`() {
        val items = listOf(dl("recent", 100, 30), dl("old", 100, 10), dl("mid", 100, 20))
        val evicted = Eviction.plan(items, usedBytes = 300, limitBytes = 150, evictLeastRecentlyPlayed = true)
        assertEquals(listOf("old", "mid"), evicted)
    }

    @Test
    fun `pinned downloads are never evicted even if the cap cannot be met`() {
        val items = listOf(dl("pinned", 500, 1, pinned = true), dl("rule", 100, 2))
        val evicted = Eviction.plan(items, usedBytes = 600, limitBytes = 200, evictLeastRecentlyPlayed = true)
        assertEquals(listOf("rule"), evicted)
    }

    @Test
    fun `oldest download first when LRU is disabled`() {
        val items = listOf(dl("a", 100, lastAccess = 50, created = 3), dl("b", 100, lastAccess = 5, created = 1))
        val evicted = Eviction.plan(items, usedBytes = 200, limitBytes = 150, evictLeastRecentlyPlayed = false)
        assertEquals(listOf("b"), evicted)
    }
}
