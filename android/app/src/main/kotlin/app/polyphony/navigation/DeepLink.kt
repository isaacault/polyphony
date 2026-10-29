package app.polyphony.navigation

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Links Polyphony opens from outside the app: launcher shortcuts (res/xml/shortcuts.xml),
 * notification taps, other apps, `adb am start`.
 */
sealed interface DeepLink {

    /** `polyphony://player`: the Now Playing sheet. */
    data object Player : DeepLink

    /** `polyphony://search`: the Search tab. */
    data object Search : DeepLink

    /** `polyphony://shuffle`: shuffle-all playback, like Library's "Shuffle all". */
    data object Shuffle : DeepLink

    /**
     * `polyphony://resume`: the most recent collection where it was left, like Home's first
     * Continue listening card (the dynamic launcher shortcut, ResumeShortcut).
     */
    data object Resume : DeepLink

    /**
     * `polyphony://album/{server}/{albumId}`: an album on one of the configured servers. {server} is
     * the host of that server's address (continuo's "Ready to play" notification sends e.g.
     * `music-api.ault.me`) or a Polyphony server id.
     */
    data class Album(val server: String, val albumId: String) : DeepLink

    /**
     * `polyphony://requests` or `polyphony://requests/{id}`: the Requests screen of the active
     * continuo server, scrolled to [id] (continuo's "needs a look" notifications carry this).
     */
    data class Requests(val id: Long?) : DeepLink

    /** `polyphony://inbox`: the continuo notification inbox (#118) of the active server. */
    data object Inbox : DeepLink

    /**
     * The notification a link was opened from, when Polyphony posted that notification itself
     * (#118): opening it marks the notification read on [serverId] and makes that server active.
     * Carried as query parameters, which [parse] ignores, so any link can have one.
     */
    data class Receipt(val serverId: String, val notificationId: Long)

    companion object {
        fun parse(link: String): DeepLink? {
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (!uri.scheme.equals("polyphony", ignoreCase = true)) return null
            val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            return when (uri.host?.lowercase()) {
                "player" -> Player
                "search" -> Search
                "shuffle" -> Shuffle
                "resume" -> Resume
                "inbox" -> Inbox.takeIf { segments.isEmpty() }
                "album" -> segments.takeIf { it.size == 2 }?.let { (server, albumId) -> Album(server, albumId) }
                "requests" -> when (segments.size) {
                    0 -> Requests(null)
                    1 -> segments[0].toLongOrNull()?.let { Requests(it) }
                    else -> null
                }
                else -> null
            }
        }

        /**
         * The configured server [link] refers to: an exact id first, else the server whose address
         * has that host (preferring a continuo-linked one if the same host was added twice).
         */
        fun serverFor(link: Album, servers: List<Server>): Server? =
            servers.firstOrNull { it.id == link.server }
                ?: servers
                    .filter { hostOf(it.baseUrl).equals(link.server, ignoreCase = true) }
                    .maxByOrNull { if (it.options[ServerOptions.CONTINUO] == "true") 1 else 0 }

        /** [link] with a [Receipt] appended, for the notifications Polyphony posts. */
        fun withReceipt(link: String, serverId: String, notificationId: Long): String {
            val separator = if ('?' in link) '&' else '?'
            return link + separator + RECEIPT_SERVER + "=" + URLEncoder.encode(serverId, "UTF-8") +
                "&" + RECEIPT_NOTIFICATION + "=" + notificationId
        }

        /** The [Receipt] on [link], if it has a complete one. */
        fun receiptOf(link: String): Receipt? {
            val query = runCatching { URI(link).rawQuery }.getOrNull() ?: return null
            val params = query.split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                parts[0] to (runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: return@mapNotNull null)
            }.toMap()
            val serverId = params[RECEIPT_SERVER]?.takeIf { it.isNotBlank() } ?: return null
            val notificationId = params[RECEIPT_NOTIFICATION]?.toLongOrNull() ?: return null
            return Receipt(serverId, notificationId)
        }

        private const val RECEIPT_SERVER = "fromServer"
        private const val RECEIPT_NOTIFICATION = "notification"

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
    }
}
