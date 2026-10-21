package app.polyphony.inbox

import app.polyphony.core.domain.model.InboxNotification
import app.polyphony.core.domain.model.InboxPage
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One poll of continuo's inbox (#118) for every continuo-linked server, and only those: fetch
 * what's newer than the last id seen, update the unread badge, post the new ones as system
 * notifications, move the cursor. Servers are polled one after another under one lock, so the
 * periodic job and the on-open poll never post the same item twice.
 */
@Singleton
class InboxPoller @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val cursors: InboxCursors,
    private val badges: InboxBadges,
    private val notifications: RequestNotifications,
) {
    private val lock = Mutex()

    /** True when every linked server answered (or there are none). */
    suspend fun pollAll(): Boolean = lock.withLock {
        var ok = true
        servers.servers.first().filter { requests.supports(it) }.forEach { server ->
            ok = poll(server) && ok
        }
        ok
    }

    private suspend fun poll(server: Server): Boolean = try {
        val lastSeen = cursors.lastSeen(server.id)
        val page = requests.notificationsSince(server, lastSeen ?: 0L)
        val fresh = page.items
        badges.set(server.id, page.unreadCount)
        InboxLogic.toPost(fresh, lastSeen).forEach { notifications.post(server, it) }
        // Advance even when notifications can't be shown, so granting the permission later doesn't
        // replay a backlog.
        cursors.advance(server.id, maxOf(page.cursor, fresh.maxOfOrNull { it.id } ?: 0L))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Offline, signed out (ProviderException.Unauthorized), server down: try again next time.
        false
    }
}

/**
 * Every notification above [since], following continuo's pages (capped at [maxPages] of
 * [pageSize]), as one page: oldest first, the last cursor, the latest unread count.
 */
internal suspend fun RequestRepository.notificationsSince(
    server: Server,
    since: Long,
    pageSize: Int = 200,
    maxPages: Int = 10,
): InboxPage {
    val items = mutableListOf<InboxNotification>()
    var cursor = since
    var unread = 0
    var pages = 0
    while (true) {
        val page = notifications(server, since = cursor, limit = pageSize)
        items += page.items
        unread = page.unreadCount
        pages++
        val advanced = page.cursor > cursor
        if (advanced) cursor = page.cursor
        if (page.items.size < pageSize || !advanced || pages >= maxPages) break
    }
    return InboxPage(items, cursor, unread)
}
