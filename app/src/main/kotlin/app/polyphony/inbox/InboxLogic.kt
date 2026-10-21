package app.polyphony.inbox

import app.polyphony.core.domain.model.InboxNotification
import app.polyphony.navigation.DeepLink

/** Where tapping an inbox row goes, on the server the inbox belongs to. */
sealed interface InboxTarget {
    data class Album(val albumId: String) : InboxTarget

    /** The Requests screen, scrolled to [id] when there is one. */
    data class Request(val id: Long?) : InboxTarget

    data object None : InboxTarget
}

/** Pure rules for the continuo inbox (#118) and its background poll, kept apart for unit tests. */
object InboxLogic {

    /** At most this many system notifications per poll; the rest wait in the inbox. */
    const val MAX_POSTS_PER_POLL = 5

    fun newestFirst(items: List<InboxNotification>): List<InboxNotification> =
        items.distinctBy { it.id }.sortedByDescending { it.id }

    fun target(item: InboxNotification): InboxTarget {
        item.libraryAlbumId?.let { return InboxTarget.Album(it) }
        return when (val link = item.deepLink?.let { DeepLink.parse(it) }) {
            is DeepLink.Album -> InboxTarget.Album(link.albumId)
            is DeepLink.Requests -> InboxTarget.Request(link.id ?: item.requestId)
            else -> item.requestId?.let { InboxTarget.Request(it) } ?: InboxTarget.None
        }
    }

    /** The link a system notification opens: continuo's, else the request, else the inbox. */
    fun link(item: InboxNotification): String =
        item.deepLink?.takeIf { DeepLink.parse(it) != null }
            ?: item.requestId?.let { "polyphony://requests/$it" }
            ?: "polyphony://inbox"

    /**
     * Which of [fresh] (everything above [lastSeen]) to post as system notifications. Nothing on
     * the very first poll of a server ([lastSeen] null): that only sets the baseline, so linking an
     * account doesn't replay its history. Read ones are skipped, a request with several new
     * notifications posts only its newest (they would replace each other anyway), newest first.
     */
    fun toPost(fresh: List<InboxNotification>, lastSeen: Long?, max: Int = MAX_POSTS_PER_POLL): List<InboxNotification> {
        if (lastSeen == null) return emptyList()
        return newestFirst(fresh)
            .filter { it.id > lastSeen && !it.read }
            .distinctBy { it.requestId ?: -it.id }
            .take(max)
    }

    /** The tag a system notification is posted under: one per request, so a newer one replaces it. */
    fun tag(serverId: String, item: InboxNotification): String =
        item.requestId?.let { "request-$serverId-$it" } ?: "notification-$serverId-${item.id}"

    /** [items] with [ids] (every item when null) marked read. */
    fun markRead(items: List<InboxNotification>, ids: Set<Long>?): List<InboxNotification> =
        items.map { if (!it.read && (ids == null || it.id in ids)) it.copy(read = true) else it }
}
