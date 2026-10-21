package app.polyphony.core.domain.model

/**
 * One row of a continuo account's notification inbox (#118): a request became available, needs a
 * look, or couldn't be added. [type] is continuo's (request_available, request_needs_attention,
 * request_error, or one a newer server adds).
 */
data class InboxNotification(
    val id: Long,
    val type: String,
    val title: String,
    val body: String?,
    /** Epoch millis, when the server sent a parseable timestamp. */
    val createdAtMillis: Long?,
    val read: Boolean,
    /** `polyphony://album/{server}/{albumId}` or `polyphony://requests/{id}`, if continuo sent one. */
    val deepLink: String?,
    /** The library album to open (an available request). */
    val libraryAlbumId: String?,
    val requestId: Long?,
)

/**
 * A page of the inbox, oldest first (continuo orders by id). [cursor] is the `since` for the next
 * page (the last id returned, or the `since` asked for when nothing was newer); [unreadCount]
 * covers the whole inbox, not just this page.
 */
data class InboxPage(
    val items: List<InboxNotification>,
    val cursor: Long,
    val unreadCount: Int,
)
