package app.polyphony.core.scrobble.server

import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What the server said to a counted play. */
sealed interface ServerAnswer {
    /** It has the play, and scrobbled it itself to [covered]. */
    data class Took(val covered: Set<ScrobbleService>) : ServerAnswer

    /** It couldn't be asked (offline, down, busy): ask again later. */
    data object Later : ServerAnswer

    /** It won't ever take this play (the server or the song is gone): nothing was covered. */
    data object Never : ServerAnswer
}

/** Telling the server what plays: Navidrome for a library song, continuo for a Play now song. */
interface ServerPlays {
    suspend fun played(serverId: String, trackId: String, playedAtMs: Long): ServerAnswer

    /** Best-effort, never throws: the services the server told what's playing. */
    suspend fun nowPlaying(serverId: String, trackId: String): Set<ScrobbleService>
}

/**
 * A library song goes to Navidrome (Subsonic `scrobble`, with the time it was played), which
 * scrobbles it to the accounts linked in Navidrome; continuo says which those are
 * ([app.polyphony.core.domain.model.ScrobbleLinks.library]), asked at most every [LINKS_TTL_MS].
 * A Play now song goes to continuo, which answers with the services it reached.
 */
@Singleton
class ServerReports @Inject constructor(
    private val servers: ServerRepository,
    private val providers: ProviderRegistry,
    private val requests: RequestRepository,
) : ServerPlays {

    private val libraryLinks = ConcurrentHashMap<String, Pair<Long, Set<ScrobbleService>>>()

    override suspend fun played(serverId: String, trackId: String, playedAtMs: Long): ServerAnswer {
        val server = serverOf(serverId) ?: return ServerAnswer.Never
        return try {
            val stream = PlayNowIds.streamId(trackId)
            if (stream != null) {
                if (!requests.supports(server)) return ServerAnswer.Never
                ServerAnswer.Took(requests.scrobblePlayNow(server, stream, playedAtMs))
            } else {
                providers.forServer(server).reportPlayed(trackId, playedAtMs)
                ServerAnswer.Took(libraryCovered(server))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (worthRetrying(e)) ServerAnswer.Later else ServerAnswer.Never
        }
    }

    override suspend fun nowPlaying(serverId: String, trackId: String): Set<ScrobbleService> = try {
        val server = serverOf(serverId)
        val stream = PlayNowIds.streamId(trackId)
        when {
            server == null -> emptySet()
            stream != null -> if (requests.supports(server)) requests.scrobblePlayNow(server, stream, null) else emptySet()
            else -> {
                providers.forServer(server).reportNowPlaying(trackId)
                libraryCovered(server)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptySet()
    }

    private suspend fun serverOf(id: String): Server? = runCatching { servers.servers.first() }.getOrNull()?.firstOrNull { it.id == id }

    /** What Navidrome scrobbles library songs to for this account; nothing known off continuo. */
    private suspend fun libraryCovered(server: Server): Set<ScrobbleService> {
        if (!requests.supports(server)) return emptySet()
        val now = System.currentTimeMillis()
        libraryLinks[server.id]?.let { (at, services) -> if (now - at < LINKS_TTL_MS) return services }
        val fresh = runCatching { requests.scrobbling(server)?.library }.getOrNull()
        val services = fresh ?: libraryLinks[server.id]?.second ?: emptySet()
        if (fresh != null) libraryLinks[server.id] = now to fresh
        return services
    }

    companion object {
        const val LINKS_TTL_MS = 10 * 60_000L

        /** Offline, a server down or busy, a sign-in to renew: worth trying again. A refusal isn't. */
        fun worthRetrying(e: Exception): Boolean = when (e) {
            is IOException -> true
            is ProviderException.Network, is ProviderException.Unauthorized, is ProviderException.Unknown -> true
            is ProviderException.Server -> e.code >= 500 || e.code == 408 || e.code == 429
            else -> false
        }
    }
}
