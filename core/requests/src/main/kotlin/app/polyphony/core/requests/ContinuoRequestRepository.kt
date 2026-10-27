package app.polyphony.core.requests

import app.polyphony.core.domain.model.ArtistName
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogAlbumDetail
import app.polyphony.core.domain.model.CatalogAlbumTrack
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogArtistPage
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.ContinuoAccount
import app.polyphony.core.domain.model.ContinuoDevice
import app.polyphony.core.domain.model.InboxPage
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.PlayNowStream
import app.polyphony.core.domain.model.RemovedAlbum
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.StreamRequest
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.network.ServerHttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinuoRequestRepository internal constructor(
    private val client: ContinuoClient,
    private val signOutTimeoutMs: Long = SIGN_OUT_TIMEOUT_MS,
) : RequestRepository {

    @Inject
    constructor(clientFactory: ServerHttpClientFactory, json: Json, servers: ServerRepository) :
        this(ContinuoClient({ clientFactory.clientFor(it) }, json, servers))

    override suspend fun search(server: Server, query: String, limit: Int, quick: Boolean): CatalogResults {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val reply = client.get(server, "/v1/search?q=$q&types=album,artist,track&limit=$limit" + if (quick) "&quick=true" else "")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo search failed")
        return client.decode(SearchDto.serializer(), reply.body).toDomain(artworkHost = hostOf(server.baseUrl))
    }

    override suspend fun libraryAlbumTracklist(server: Server, libraryAlbumId: String): app.polyphony.core.domain.model.LibraryAlbumTracklist? {
        val reply = client.get(server, "/v1/library/albums/${URLEncoder.encode(libraryAlbumId, "UTF-8")}/tracklist")
        if (reply.code == 404) return null   // no release group known, or a continuo from before
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo tracklist failed")
        val dto = client.decode(LibraryTracklistDto.serializer(), reply.body)
        return app.polyphony.core.domain.model.LibraryAlbumTracklist(
            releaseGroupId = dto.musicBrainzReleaseGroupId,
            tracks = dto.tracks.map { CatalogAlbumTrack(it.position, it.discNumber, it.title, it.durationMs, it.musicBrainzRecordingId) },
        )
    }

    override suspend fun lyrics(server: Server, artist: String, title: String, album: String?, durationMs: Long?): app.polyphony.core.domain.model.Lyrics? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val query = buildString {
            append("artist=").append(enc(artist)).append("&title=").append(enc(title))
            album?.takeIf { it.isNotBlank() }?.let { append("&album=").append(enc(it)) }
            durationMs?.takeIf { it > 0 }?.let { append("&durationMs=").append(it) }
        }
        val reply = client.get(server, "/v1/library/lyrics?$query")
        if (reply.code == 404) return null   // none known, or a continuo from before lyrics
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo lyrics failed")
        return client.decode(LyricsDto.serializer(), reply.body).toDomain()
    }

    override suspend fun discover(server: Server): app.polyphony.core.domain.model.Discovery? {
        val reply = client.get(server, "/v1/discover")
        if (reply.code == 404) return null   // a continuo from before discovery
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo discover failed")
        return client.decode(DiscoverDto.serializer(), reply.body).toDomain(artworkHost = hostOf(server.baseUrl))
    }

    override suspend fun catalogArtist(server: Server, artistMbid: String): CatalogArtistPage? {
        val reply = client.get(server, "/v1/catalog/artists/${URLEncoder.encode(artistMbid, "UTF-8")}")
        // 404: an artist the catalogue doesn't know, or a continuo from before artist pages.
        if (reply.code == 404) return null
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo artist failed")
        return client.decode(CatalogArtistDto.serializer(), reply.body).toDomain(artworkHost = hostOf(server.baseUrl))
    }

    override suspend fun findArtist(server: Server, name: String): CatalogArtist? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        // continuo picks among same-name artists by what the library holds (MusicBrainz has four
        // "Peach Pit"s). An older continuo doesn't have the route (404, or 400 from the artist page
        // route taking "resolve" for an id): the plain name search stands in.
        val resolved = client.get(server, "/v1/catalog/artists/resolve?name=$q")
        if (resolved.code in 200..299) {
            val dto = client.decode(ResolvedArtistDto.serializer(), resolved.body)
            return CatalogArtist("mb:artist:${dto.musicBrainzArtistId}", dto.musicBrainzArtistId, dto.name, null, Availability.InLibrary(null))
        }
        val reply = client.get(server, "/v1/search?q=$q&types=artist&limit=10")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo search failed")
        val wanted = sameName(name)
        return client.decode(SearchDto.serializer(), reply.body).toDomain(artworkHost = hostOf(server.baseUrl))
            .artists.firstOrNull { it.musicBrainzId != null && sameName(it.name) == wanted }
    }

    override suspend fun artistAlbums(server: Server, artistMbid: String): List<CatalogAlbum> {
        val reply = client.get(server, "/v1/catalog/artists/${URLEncoder.encode(artistMbid, "UTF-8")}/albums")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo discography failed")
        val host = hostOf(server.baseUrl)
        return client.decode(ListSerializer(HitDto.serializer()), reply.body).map { it.toCatalogAlbum(host) }
    }

    override suspend fun catalogAlbum(server: Server, releaseGroupId: String): CatalogAlbumDetail {
        val reply = client.get(server, "/v1/catalog/albums/${URLEncoder.encode(releaseGroupId, "UTF-8")}")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo album failed")
        val hit = client.decode(HitDto.serializer(), reply.body)
        return CatalogAlbumDetail(
            album = hit.toCatalogAlbum(hostOf(server.baseUrl)),
            tracks = hit.tracks.map { CatalogAlbumTrack(it.position, it.discNumber, it.title, it.durationMs, it.musicBrainzRecordingId) },
        )
    }

    override suspend fun startPlayNow(
        server: Server,
        recordingId: String,
        albumReleaseGroupId: String?,
        title: String,
        artist: String,
        durationMs: Long?,
        queued: Boolean,
    ): PlayNowStream {
        val body = Json.encodeToString(PlayNowBody.serializer(), PlayNowBody(recordingId, albumReleaseGroupId, title, artist, durationMs, queued))
        val reply = client.post(server, "/v1/play-now", body)
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't play that song")
        return client.decode(PlayNowDto.serializer(), reply.body).toDomain()
    }

    override suspend fun playNowStatus(server: Server, streamId: String): PlayNowStream {
        val reply = client.get(server, "/v1/play-now/${URLEncoder.encode(streamId, "UTF-8")}")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo lost that song")
        return client.decode(PlayNowDto.serializer(), reply.body).toDomain()
    }

    override suspend fun prioritisePlayNow(server: Server, streamIds: List<String>) {
        val reply = client.post(server, "/v1/play-now/priority", Json.encodeToString(PlayNowPriorityBody.serializer(), PlayNowPriorityBody(streamIds)))
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't reorder its downloads")
    }

    override suspend fun keepPlayNow(server: Server, streamId: String) {
        val reply = client.post(server, "/v1/play-now/${URLEncoder.encode(streamId, "UTF-8")}/keep", "{}")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't keep that song")
    }

    override suspend fun playNowStream(server: Server, streamId: String): StreamRequest = StreamRequest(
        url = server.baseUrl.trimEnd('/') + "/v1/play-now/${URLEncoder.encode(streamId, "UTF-8")}/audio",
        headers = mapOf("Authorization" to "Bearer ${client.accessToken(server, force = false)}"),
    )

    override suspend fun requestTrack(server: Server, recordingId: String, albumReleaseGroupId: String): RequestOutcome =
        sendRequest(server, Json.encodeToString(CreateTrackRequestBody.serializer(), CreateTrackRequestBody("track", recordingId, albumReleaseGroupId)))

    override suspend fun requestAlbum(server: Server, releaseGroupId: String): RequestOutcome =
        sendRequest(server, Json.encodeToString(CreateRequestBody.serializer(), CreateRequestBody("album", releaseGroupId)))

    private suspend fun sendRequest(server: Server, body: String): RequestOutcome {
        val reply = client.post(server, "/v1/requests", body)
        return when {
            reply.code in 200..299 -> RequestOutcome.Requested(client.decode(CreatedRequestDto.serializer(), reply.body).id)
            reply.code == 409 -> {
                val err = runCatching { client.decode(ErrorEnvelope.serializer(), reply.body).error }.getOrNull()
                when (err?.code) {
                    "in_library" -> RequestOutcome.InLibrary(err?.navidromeAlbumId)
                    "added_to_library" -> RequestOutcome.Added
                    "already_requested" -> RequestOutcome.AlreadyRequested(err?.requestId)
                    else -> throw client.serverError(reply, "continuo refused the request")
                }
            }
            else -> throw client.serverError(reply, "continuo couldn't request that album")
        }
    }

    override suspend fun requests(server: Server, activeOnly: Boolean): List<MusicRequest> {
        val scope = if (activeOnly) "active" else "all"
        val reply = client.get(server, "/v1/requests?scope=$scope&limit=200")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't list requests")
        val host = hostOf(server.baseUrl)
        return client.decode(RequestListDto.serializer(), reply.body).items.map { it.toDomain(host) }
    }

    override fun events(server: Server): Flow<RequestEvent> = flow {
        var failures = 0
        var refreshToken = false
        var reconnecting = false
        while (currentCoroutineContext().isActive) {
            try {
                // A refused or expired session ends the stream: ProviderException.Unauthorized escapes.
                val token = client.accessToken(server, force = refreshToken)
                refreshToken = false
                client.events(server, "/v1/events", token).collect { (type, data) ->
                    when (type) {
                        "hello" -> {
                            failures = 0
                            // Anything may have changed while disconnected.
                            if (reconnecting) emit(RequestEvent.Resync)
                            reconnecting = false
                        }
                        "request.updated" -> runCatching { client.decode(RequestUpdateDto.serializer(), data) }
                            .getOrNull()?.let { emit(it.toDomain()) }
                        "request.available" -> emit(RequestEvent.Resync)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException.Unauthorized) {
                throw e
            } catch (e: ContinuoClient.StreamUnauthorized) {
                refreshToken = true
            } catch (e: Exception) {
                // Network blip, server restart: reconnect below.
            }
            reconnecting = true
            failures++
            delay((1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000L))
        }
    }

    override suspend fun perform(server: Server, requestId: Long, action: RequestAction): String {
        val base = "/v1/requests/$requestId"
        val reply = when (action) {
            RequestAction.SEARCH_NOW -> client.post(server, "$base/search-now", "{}")
            RequestAction.RETRY -> client.post(server, "$base/retry", "{}")
            RequestAction.DROP_DOWNLOAD -> client.post(server, "$base/drop-download", "{}")
            RequestAction.REMOVE -> client.delete(server, base)
        }
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't do that")
        return runCatching { client.decode(ActionResultDto.serializer(), reply.body).message }.getOrNull().orEmpty()
    }

    override suspend fun account(server: Server): ContinuoAccount {
        val reply = client.get(server, "/v1/me")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't say who you are")
        val me = client.decode(MeDto.serializer(), reply.body)
        return ContinuoAccount(me.user.username, me.user.role, me.capabilities.toSet())
    }

    override suspend fun removeAlbum(server: Server, libraryAlbumId: String): RemovedAlbum {
        val reply = client.post(server, "/v1/library/albums/${URLEncoder.encode(libraryAlbumId, "UTF-8")}/remove", "{}")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't remove the album")
        return client.decode(RemovedDto.serializer(), reply.body).toDomain()
    }

    override suspend fun removedAlbums(server: Server): List<RemovedAlbum> {
        val reply = client.get(server, "/v1/library/removed")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't list removed albums")
        return client.decode(ListSerializer(RemovedDto.serializer()), reply.body).map { it.toDomain() }
    }

    override suspend fun restoreAlbum(server: Server, removedId: Long) {
        val reply = client.post(server, "/v1/library/removed/$removedId/restore", "{}")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't restore the album")
    }

    override suspend fun artistNames(server: Server): List<ArtistName> {
        val reply = client.get(server, "/v1/library/artist-names")
        if (reply.code == 404) return emptyList()  // a continuo from before names
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't list artist names")
        return client.decode(ListSerializer(ArtistNameDto.serializer()), reply.body)
            .mapNotNull { d -> d.originalName?.takeIf { it.isNotBlank() }?.let { ArtistName(d.name, it, d.sortName) } }
    }

    override suspend fun devices(server: Server): List<ContinuoDevice> {
        val reply = client.get(server, "/v1/devices")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't list devices")
        return client.decode(ListSerializer(DeviceDto.serializer()), reply.body).map { it.toDomain() }
    }

    override suspend fun revokeDevice(server: Server, deviceId: String) {
        val reply = client.delete(server, "/v1/devices/" + URLEncoder.encode(deviceId, "UTF-8"))
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't sign that device out")
    }

    override suspend fun revokeOtherDevices(server: Server): Int {
        val reply = client.post(server, "/v1/devices/revoke-others", "{}")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't sign the other devices out")
        return runCatching { client.decode(RevokedDto.serializer(), reply.body).revoked }.getOrDefault(0)
    }

    override suspend fun signOut(server: Server) {
        if (!server.isContinuo || server.needsContinuoSignIn) return
        try {
            withTimeoutOrNull(signOutTimeoutMs) { client.logout(server) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: the device stays listed until it's revoked from another one.
        }
    }

    private companion object {
        /** Removing a server never waits longer than this for continuo (#120). */
        const val SIGN_OUT_TIMEOUT_MS = 5_000L
    }

    override suspend fun notifications(server: Server, since: Long, unreadOnly: Boolean, limit: Int, newestFirst: Boolean): InboxPage {
        val from = since.coerceAtLeast(0)
        val order = if (newestFirst) "&order=desc" else ""
        val reply = client.get(server, "/v1/notifications?since=$from&unread=$unreadOnly&limit=${limit.coerceIn(1, 500)}$order")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't list notifications")
        return client.decode(NotificationListDto.serializer(), reply.body).toDomain(from)
    }

    override suspend fun markRead(server: Server, ids: List<Long>): Int {
        if (ids.isEmpty()) return notifications(server, limit = 1).unreadCount
        return postRead(server, Json.encodeToString(MarkReadIdsBody.serializer(), MarkReadIdsBody(ids.distinct())))
    }

    override suspend fun markAllRead(server: Server): Int = postRead(server, """{"all":true}""")

    private suspend fun postRead(server: Server, body: String): Int {
        val reply = client.post(server, "/v1/notifications/read", body)
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo couldn't mark notifications read")
        return client.decode(MarkReadDto.serializer(), reply.body).unreadCount
    }
}

/** An artist name compared loosely: case and punctuation don't make two artists. */
internal fun sameName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
