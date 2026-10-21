package app.polyphony.inbox

import app.polyphony.core.domain.model.InboxNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxLogicTest {

    private fun item(
        id: Long,
        type: String = "request_available",
        read: Boolean = false,
        deepLink: String? = null,
        albumId: String? = null,
        requestId: Long? = id + 100,
    ) = InboxNotification(id, type, "Title $id", "Body", null, read, deepLink, albumId, requestId)

    @Test
    fun `the first poll only sets the baseline`() {
        assertTrue(InboxLogic.toPost(listOf(item(1), item(2)), lastSeen = null).isEmpty())
    }

    @Test
    fun `new unread items are posted newest first, one per request, capped`() {
        val fresh = listOf(
            item(3, requestId = 7),
            item(4, read = true),
            item(5, requestId = 7),
            item(6, requestId = null),
            item(7, requestId = null),
        )
        assertEquals(listOf(7L, 6L, 5L), InboxLogic.toPost(fresh, lastSeen = 2).map { it.id })
        // Anything at or below the cursor was already handled.
        assertEquals(listOf(7L, 6L), InboxLogic.toPost(fresh, lastSeen = 5).map { it.id })
        val many = (10L..30L).map { item(it) }
        assertEquals((30L downTo 26L).toList(), InboxLogic.toPost(many, lastSeen = 9).map { it.id })
    }

    @Test
    fun `targets follow the action`() {
        assertEquals(
            InboxTarget.Album("nd-1"),
            InboxLogic.target(item(1, deepLink = "polyphony://album/music-api.example.com/nd-1", albumId = "nd-1")),
        )
        // A link without the album id spelled out still opens the album.
        assertEquals(InboxTarget.Album("nd-2"), InboxLogic.target(item(1, deepLink = "polyphony://album/host/nd-2")))
        assertEquals(
            InboxTarget.Request(3),
            InboxLogic.target(item(1, type = "request_needs_attention", deepLink = "polyphony://requests/3")),
        )
        assertEquals(InboxTarget.Request(9), InboxLogic.target(item(1, type = "request_error", requestId = 9)))
        assertEquals(InboxTarget.None, InboxLogic.target(item(1, type = "news", requestId = null)))
    }

    @Test
    fun `system notifications always link somewhere`() {
        assertEquals("polyphony://album/h/nd-1", InboxLogic.link(item(1, deepLink = "polyphony://album/h/nd-1")))
        assertEquals("polyphony://requests/9", InboxLogic.link(item(1, deepLink = "https://elsewhere", requestId = 9)))
        assertEquals("polyphony://inbox", InboxLogic.link(item(1, requestId = null)))
    }

    @Test
    fun `tags group by request so a newer one replaces the older`() {
        assertEquals(InboxLogic.tag("s1", item(1, requestId = 7)), InboxLogic.tag("s1", item(2, requestId = 7)))
        assertTrue(InboxLogic.tag("s1", item(1, requestId = 7)) != InboxLogic.tag("s2", item(1, requestId = 7)))
        assertTrue(InboxLogic.tag("s1", item(1, requestId = null)) != InboxLogic.tag("s1", item(2, requestId = null)))
    }

    @Test
    fun `newest first and mark read`() {
        val items = InboxLogic.newestFirst(listOf(item(1), item(3), item(2), item(3)))
        assertEquals(listOf(3L, 2L, 1L), items.map { it.id })
        assertEquals(listOf(false, true, false), InboxLogic.markRead(items, setOf(2L)).map { it.read })
        assertTrue(InboxLogic.markRead(items, null).all { it.read })
    }
}
