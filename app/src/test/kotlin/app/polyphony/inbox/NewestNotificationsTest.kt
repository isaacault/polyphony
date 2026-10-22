package app.polyphony.inbox

import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.ContinuoDevice
import app.polyphony.core.domain.model.InboxNotification
import app.polyphony.core.domain.model.InboxPage
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.repository.RequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NewestNotificationsTest {

    private val server = Server(
        id = "s1", type = ServerType.SUBSONIC, name = "continuo", baseUrl = "https://music-api.example.com",
        username = "isaac", credential = "secret", deviceId = "d1",
    )

    /** An inbox of ids 1..[size]; [honoursOrder] false plays a server from before `order=desc`. */
    private class FakeInbox(size: Long, private val honoursOrder: Boolean) : RequestRepository {
        val all = (1L..size).toList()
        val calls = mutableListOf<Pair<Long, Boolean>>()

        override suspend fun notifications(server: Server, since: Long, unreadOnly: Boolean, limit: Int, newestFirst: Boolean): InboxPage {
            calls += since to newestFirst
            val above = all.filter { it > since }
            val ids = if (newestFirst && honoursOrder) above.reversed().take(limit) else above.take(limit)
            return InboxPage(ids.map { InboxNotification(it, "t", "T$it", null, null, false, null, null, null) }, ids.maxOrNull() ?: since, 3)
        }

        override suspend fun search(server: Server, query: String, limit: Int): CatalogResults = error("unused")
        override suspend fun requestAlbum(server: Server, releaseGroupId: String): RequestOutcome = error("unused")
        override suspend fun requests(server: Server, activeOnly: Boolean): List<MusicRequest> = error("unused")
        override fun events(server: Server): Flow<RequestEvent> = error("unused")
        override suspend fun perform(server: Server, requestId: Long, action: RequestAction): String = error("unused")
        override suspend fun markRead(server: Server, ids: List<Long>): Int = error("unused")
        override suspend fun markAllRead(server: Server): Int = error("unused")
        override suspend fun devices(server: Server): List<ContinuoDevice> = error("unused")
        override suspend fun revokeDevice(server: Server, deviceId: String): Unit = error("unused")
        override suspend fun revokeOtherDevices(server: Server): Int = error("unused")
        override suspend fun signOut(server: Server): Unit = error("unused")
    }

    @Test
    fun `a server that knows order=desc gives the newest page in one call`() = runTest {
        val inbox = FakeInbox(size = 1200, honoursOrder = true)
        val page = inbox.newestNotifications(server, limit = 500)
        assertEquals((1200L downTo 701L).toList(), page.items.map { it.id })
        assertEquals(1200L, page.cursor)
        assertEquals(listOf(0L to true), inbox.calls)
    }

    @Test
    fun `an older server that ignores order is read oldest first as before`() = runTest {
        val inbox = FakeInbox(size = 1200, honoursOrder = false)
        val page = inbox.newestNotifications(server, limit = 500)
        assertEquals((1L..1200L).toList(), page.items.map { it.id })
        assertEquals(1200L, page.cursor)
        assertEquals(3, page.unreadCount)
        assertEquals(listOf(0L to true, 500L to false, 1000L to false), inbox.calls)
    }

    @Test
    fun `a short page is the whole inbox in either order`() = runTest {
        for (honours in listOf(true, false)) {
            val inbox = FakeInbox(size = 40, honoursOrder = honours)
            val page = inbox.newestNotifications(server, limit = 500)
            assertEquals((1L..40L).toSet(), page.items.map { it.id }.toSet())
            assertEquals(40L, page.cursor)
            assertEquals(1, inbox.calls.size)
        }
    }
}
