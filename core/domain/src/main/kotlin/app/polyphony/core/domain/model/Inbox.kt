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
    /**
     * Where continuo already delivered it (its `deliveredVia`, lower-cased): [HOME_ASSISTANT] when
     * Home Assistant accepted the push, so the phone already shows it, and "sse" (informational).
     * Empty on servers that don't report it.
     */
    val deliveredVia: Set<String> = emptySet(),
) {
    /** Home Assistant already put it on the phone: posting it locally would show it twice. */
    val deliveredByHomeAssistant: Boolean get() = HOME_ASSISTANT in deliveredVia

    companion object {
        const val HOME_ASSISTANT = "homeassistant"
    }
}

/**
 * A page of the inbox, in the order asked for (continuo orders by id, oldest first by default).
 * [cursor] is the `since` for the next "anything new?" request (the highest id returned, or the
 * `since` asked for when nothing was newer); [unreadCount] covers the whole inbox, not just this page.
 */
data class InboxPage(
    val items: List<InboxNotification>,
    val cursor: Long,
    val unreadCount: Int,
)
