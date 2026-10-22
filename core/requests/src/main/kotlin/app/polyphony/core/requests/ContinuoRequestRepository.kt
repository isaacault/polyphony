package app.polyphony.core.requests

import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogResults
import app.polyphony.core.domain.model.ContinuoDevice
import app.polyphony.core.domain.model.InboxPage
import app.polyphony.core.domain.model.MusicRequest
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
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

    override suspend fun search(server: Server, query: String, limit: Int): CatalogResults {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val reply = client.get(server, "/v1/search?q=$q&types=album,artist,track&limit=$limit")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo search failed")
        return client.decode(SearchDto.serializer(), reply.body).toDomain(artworkHost = hostOf(server.baseUrl))
    }

    override suspend fun artistAlbums(server: Server, artistMbid: String): List<CatalogAlbum> {
        val reply = client.get(server, "/v1/catalog/artists/${URLEncoder.encode(artistMbid, "UTF-8")}/albums")
        if (reply.code !in 200..299) throw client.serverError(reply, "continuo discography failed")
        val host = hostOf(server.baseUrl)
        return client.decode(ListSerializer(HitDto.serializer()), reply.body).map { it.toCatalogAlbum(host) }
    }

    override suspend fun requestAlbum(server: Server, releaseGroupId: String): RequestOutcome {
        val body = Json.encodeToString(CreateRequestBody.serializer(), CreateRequestBody("album", releaseGroupId))
        val reply = client.post(server, "/v1/requests", body)
        return when {
            reply.code in 200..299 -> RequestOutcome.Requested(client.decode(CreatedRequestDto.serializer(), reply.body).id)
            reply.code == 409 -> {
                val err = runCatching { client.decode(ErrorEnvelope.serializer(), reply.body).error }.getOrNull()
                when (err?.code) {
                    "in_library" -> RequestOutcome.InLibrary(err?.navidromeAlbumId)
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
