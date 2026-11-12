package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Artist
import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ScrobbleAuthException
import app.polyphony.core.domain.repository.ScrobbleRepository
import app.polyphony.core.scrobble.db.QueuedScrobbleEntity
import app.polyphony.core.scrobble.db.ScrobbleDao
import app.polyphony.core.scrobble.db.ServerPlayEntity
import app.polyphony.core.scrobble.lastfm.LastFmClient
import app.polyphony.core.scrobble.lastfm.LastFmConfig
import app.polyphony.core.scrobble.listenbrainz.ListenBrainzClient
import app.polyphony.core.scrobble.queue.toDomain
import app.polyphony.core.scrobble.queue.toEntity
import app.polyphony.core.scrobble.server.ServerPlays
import app.polyphony.core.scrobble.work.ScrobbleScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrobbleRepositoryImpl @Inject constructor(
    private val dao: ScrobbleDao,
    private val library: LibraryRepository,
    private val listenBrainz: ListenBrainzClient,
    private val lastFm: LastFmClient,
    private val lastFmConfig: LastFmConfig,
    private val scheduler: ScrobbleScheduler,
    private val json: Json,
    private val server: ServerPlays,
) : ScrobbleRepository {

    override val accounts: Flow<List<ScrobbleAccount>> =
        dao.observeAccounts().map { rows -> rows.mapNotNull { it.toDomain() }.filter { it.service in offered } }

    override val pending: Flow<Map<ScrobbleService, Int>> = dao.observePending().map { rows ->
        rows.mapNotNull { row -> ScrobbleService.entries.firstOrNull { it.name == row.service }?.let { it to row.count } }.toMap()
    }

    override val lastFmAvailable: Boolean get() = lastFmConfig.available

    /** Last.fm only in a build with an API key: an account restored into one without it stays dormant. */
    private val offered: Set<ScrobbleService>
        get() = if (lastFmAvailable) ScrobbleService.entries.toSet() else setOf(ScrobbleService.LISTENBRAINZ)

    private suspend fun connected(): List<ScrobbleAccount> =
        dao.accounts().mapNotNull { it.toDomain() }.filter { it.service in offered }

    override suspend fun connectListenBrainz(token: String): ScrobbleAccount {
        val user = listenBrainz.validateToken(token.trim())
        return connect(ScrobbleAccount(ScrobbleService.LISTENBRAINZ, user, token.trim()))
    }

    override suspend fun connectLastFm(username: String, password: String): ScrobbleAccount {
        if (!lastFmAvailable) throw ScrobbleAuthException("This build has no Last.fm API key")
        val (name, key) = lastFm.mobileSession(username, password)
        return connect(ScrobbleAccount(ScrobbleService.LASTFM, name, key))
    }

    private suspend fun connect(account: ScrobbleAccount): ScrobbleAccount {
        // Connecting again keeps the now-playing choice and sends what waited for it.
        val previous = dao.accounts().firstOrNull { it.service == account.service.name }
        val saved = account.copy(nowPlaying = previous?.nowPlaying ?: account.nowPlaying)
        dao.upsertAccount(saved.toEntity())
        scheduler.drainNow()
        return saved
    }

    override suspend fun disconnect(service: ScrobbleService) {
        dao.deleteAccount(service.name)
        dao.clearQueue(service.name)
    }

    override suspend fun setNowPlaying(service: ScrobbleService, enabled: Boolean) = dao.setNowPlaying(service.name, enabled)

    override suspend fun nowPlaying(track: Track, toServer: Boolean) {
        // The server first: the services it told needn't hear it from this phone too.
        val covered = if (toServer) server.nowPlaying(track.serverId, track.id) else emptySet()
        val accounts = runCatching { connected() }.getOrDefault(emptyList())
            .filter { it.nowPlaying && !it.needsReconnect && it.service !in covered }
        if (accounts.isEmpty()) return
        val scrobble = scrobbleOf(track, System.currentTimeMillis()) ?: return
        for (account in accounts) {
            val client = client(account.service)
            if (!client.accepts(scrobble)) continue
            try {
                client.nowPlaying(account, scrobble)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Now playing is a courtesy: offline or refused, it's simply not shown.
            }
        }
    }

    override suspend fun played(track: Track, playedAtEpochMs: Long, toServer: Boolean) {
        val accounts = runCatching { connected() }.getOrDefault(emptyList())
        val payload = if (accounts.isEmpty()) null
        else scrobbleOf(track, playedAtEpochMs)?.let { json.encodeToString(Scrobble.serializer(), it) }
        val now = System.currentTimeMillis()
        if (toServer) {
            // Kept until the server has it; then on to the accounts it doesn't scrobble to itself.
            dao.enqueueServerPlay(
                ServerPlayEntity(serverId = track.serverId, trackId = track.id, playedAt = playedAtEpochMs, payload = payload, createdAt = now),
            )
        } else {
            if (payload == null) return
            // Queued even for an account waiting to be connected again: sent once it is.
            dao.enqueue(accounts.map { QueuedScrobbleEntity(service = it.service.name, payload = payload, createdAt = now) })
        }
        runCatching { scheduler.drain() }
    }

    override suspend fun all(): List<ScrobbleAccount> = dao.accounts().mapNotNull { it.toDomain() }

    override suspend fun restore(accounts: List<ScrobbleAccount>) {
        if (accounts.isEmpty()) return
        accounts.forEach { dao.upsertAccount(it.toEntity()) }
        if (dao.pendingCount() > 0) runCatching { scheduler.drain() }
    }

    private fun client(service: ScrobbleService): ScrobbleClient = when (service) {
        ScrobbleService.LISTENBRAINZ -> listenBrainz
        ScrobbleService.LASTFM -> lastFm
    }

    private suspend fun scrobbleOf(track: Track, at: Long): Scrobble? {
        val album: Album? = track.albumId?.let { id -> runCatching { library.album(id).first() }.getOrNull() }
        val artist: Artist? = track.artistId?.let { id -> runCatching { library.artist(id).first() }.getOrNull() }
        return Scrobble.of(track, at, album, artist)
    }
}
