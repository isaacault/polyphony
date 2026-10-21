package app.polyphony.navigation

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import java.net.URI

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

    companion object {
        fun parse(link: String): DeepLink? {
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (!uri.scheme.equals("polyphony", ignoreCase = true)) return null
            val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            return when (uri.host?.lowercase()) {
                "player" -> Player
                "search" -> Search
                "shuffle" -> Shuffle
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

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
    }
}
