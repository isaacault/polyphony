package app.polyphony.core.requests

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Authenticated calls to a continuo server's `/v1` API.
 *
 * Sign-in (provider:subsonic's ContinuoSignIn) stores a refresh token in
 * [ServerOptions.CONTINUO_REFRESH_TOKEN]. Each access token (1 h) comes from
 * `POST /v1/auth/refresh`, which also rotates the refresh token: the new one is persisted before
 * the access token is used, and refreshes are serialised per server, because presenting an
 * already-rotated token makes continuo revoke the device (playback included).
 */
internal class ContinuoClient(
    private val callFactory: (Server) -> Call.Factory,
    private val json: Json,
    private val servers: ServerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Access(val token: String, val expiresAtMillis: Long)

    private val access = ConcurrentHashMap<String, Access>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** A response the caller decodes; [body] is read eagerly so the connection is released. */
    data class Reply(val code: Int, val body: String)

    suspend fun get(server: Server, path: String): Reply = send(server) {
        Request.Builder().url(server.baseUrl.trimEnd('/') + path).get()
    }

    suspend fun post(server: Server, path: String, jsonBody: String): Reply = send(server) {
        Request.Builder().url(server.baseUrl.trimEnd('/') + path).post(jsonBody.toRequestBody(JSON))
    }

    private suspend fun send(server: Server, build: () -> Request.Builder): Reply {
        var token = accessToken(server, force = false)
        repeat(2) { attempt ->
            val reply = execute(server, build().header("Authorization", "Bearer $token").build())
            if (reply.code != 401 || attempt == 1) return reply
            // The access token was refused (device revoked, server restarted with a new key, …):
            // refresh once and retry; a second 401 is reported to the caller.
            access.remove(server.id)
            token = accessToken(server, force = true)
        }
        error("unreachable")
    }

    internal suspend fun accessToken(server: Server, force: Boolean): String {
        val lock = locks.getOrPut(server.id) { Mutex() }
        return lock.withLock {
            access[server.id]?.takeIf { !force && it.expiresAtMillis - EXPIRY_MARGIN_MS > clock() }
                ?.let { return@withLock it.token }
            // Always read the stored token afresh: another caller may have rotated it.
            val current = servers.get(server.id) ?: server
            val refresh = current.options[ServerOptions.CONTINUO_REFRESH_TOKEN]
                ?: throw ProviderException.Unauthorized("Sign in to continuo again to use requests")
            val body = json.encodeToString(RefreshBody.serializer(), RefreshBody(refresh))
            val reply = execute(current, Request.Builder()
                .url(current.baseUrl.trimEnd('/') + "/v1/auth/refresh")
                .post(body.toRequestBody(JSON))
                .build())
            if (reply.code == 401) {
                access.remove(server.id)
                throw ProviderException.Unauthorized("continuo signed this device out; sign in again")
            }
            if (reply.code !in 200..299) throw serverError(reply, "continuo refresh failed")
            val session = decode(SessionDto.serializer(), reply.body)
            servers.update(current.copy(options = current.options + (ServerOptions.CONTINUO_REFRESH_TOKEN to session.refreshToken)))
            Access(session.accessToken, clock() + session.expiresIn * 1000L).also { access[server.id] = it }.token
        }
    }

    fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, body: String): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw ProviderException.Unknown("Unexpected answer from continuo", e)
    } catch (e: IllegalArgumentException) {
        throw ProviderException.Unknown("Unexpected answer from continuo", e)
    }

    fun serverError(reply: Reply, fallback: String): ProviderException {
        val err = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), reply.body).error }.getOrNull()
        return when (reply.code) {
            401 -> ProviderException.Unauthorized(err?.message ?: "Not signed in to continuo")
            404 -> ProviderException.NotFound(err?.message ?: "Not found")
            else -> ProviderException.Server(reply.code, err?.message ?: "$fallback (HTTP ${reply.code})")
        }
    }

    private suspend fun execute(server: Server, request: Request): Reply {
        val response = try {
            callFactory(server).newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ProviderException.Network(e.message ?: "Couldn't reach continuo", e)
        }
        return response.use { Reply(it.code, it.body.string()) }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> runCatching { response.close() } }
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
