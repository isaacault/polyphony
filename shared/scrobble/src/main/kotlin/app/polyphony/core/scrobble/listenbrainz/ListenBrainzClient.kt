package app.polyphony.core.scrobble.listenbrainz

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.repository.ScrobbleAuthException
import app.polyphony.core.scrobble.Scrobble
import app.polyphony.core.scrobble.ScrobbleClient
import app.polyphony.core.scrobble.SubmitResult
import app.polyphony.core.scrobble.http.HttpRequest
import app.polyphony.core.scrobble.http.HttpResponse
import app.polyphony.core.scrobble.http.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * ListenBrainz (https://listenbrainz.readthedocs.io/en/latest/users/api/core.html): the user token
 * goes in an `Authorization: Token` header; listens go to `submit-listens` as `playing_now`, `single`
 * or `import` (several at once).
 */
class ListenBrainzClient(
    private val http: HttpTransport,
    private val appVersion: String,
    private val baseUrl: String = BASE_URL,
) : ScrobbleClient {

    override val service = ScrobbleService.LISTENBRAINZ

    /** ListenBrainz takes up to 1000 listens a request; smaller requests stay well under its size limit. */
    override val batchLimit = 100

    /** The user [token] belongs to; throws [ScrobbleAuthException] when ListenBrainz refuses it. */
    suspend fun validateToken(token: String): String {
        val response = http.execute(HttpRequest("GET", "$baseUrl/1/validate-token", headers = auth(token)))
        if (response.code >= 500) throw IOException("ListenBrainz answered ${response.code}")
        val body = parse(response)
        val valid = body?.get("valid")?.jsonPrimitive?.booleanOrNull == true
        val user = body?.get("user_name")?.jsonPrimitive?.contentOrNull
        if (response.code != 200 || !valid || user.isNullOrBlank()) {
            throw ScrobbleAuthException(body?.get("message")?.jsonPrimitive?.contentOrNull ?: "ListenBrainz refused the token")
        }
        return user
    }

    override suspend fun nowPlaying(account: ScrobbleAccount, scrobble: Scrobble) {
        http.execute(submitRequest(account.secret, payload(PLAYING_NOW, listOf(scrobble))))
    }

    override suspend fun submit(account: ScrobbleAccount, batch: List<Scrobble>): SubmitResult {
        if (batch.isEmpty()) return SubmitResult.Accepted()
        val type = if (batch.size == 1) SINGLE else IMPORT
        return result(http.execute(submitRequest(account.secret, payload(type, batch))))
    }

    /** The `submit-listens` body: [listenType] and one entry per listen (no time for playing_now). */
    fun payload(listenType: String, listens: List<Scrobble>): JsonObject = buildJsonObject {
        put("listen_type", listenType)
        putJsonArray("payload") {
            listens.forEach { add(listen(it, withTime = listenType != PLAYING_NOW)) }
        }
    }

    private fun listen(s: Scrobble, withTime: Boolean): JsonObject = buildJsonObject {
        if (withTime) put("listened_at", s.listenedAtSeconds)
        putJsonObject("track_metadata") {
            put("artist_name", s.artist)
            put("track_name", s.track)
            s.album?.let { put("release_name", it) }
            putJsonObject("additional_info") {
                put("media_player", CLIENT)
                put("submission_client", CLIENT)
                put("submission_client_version", appVersion)
                if (s.durationMs > 0) put("duration_ms", s.durationMs)
                s.trackNumber?.let { put("tracknumber", it) }
                s.discNumber?.let { put("discnumber", it) }
                s.recordingMbid?.let { put("recording_mbid", it) }
                s.releaseMbid?.let { put("release_mbid", it) }
                if (s.artistMbids.isNotEmpty()) putJsonArray("artist_mbids") { s.artistMbids.forEach { add(it) } }
            }
        }
    }

    private fun submitRequest(token: String, body: JsonObject) = HttpRequest(
        method = "POST",
        url = "$baseUrl/1/submit-listens",
        headers = auth(token),
        body = body.toString(),
        contentType = "application/json; charset=utf-8",
    )

    private fun auth(token: String) = mapOf("Authorization" to "Token ${token.trim()}")

    private fun result(response: HttpResponse): SubmitResult = when {
        response.code in 200..299 -> SubmitResult.Accepted()
        response.code == 401 -> SubmitResult.Unauthorized
        response.code == 429 -> SubmitResult.Retry(
            "ListenBrainz rate limit",
            afterMs = response.header("X-RateLimit-Reset-In")?.toLongOrNull()?.times(1000),
        )
        response.code >= 500 || response.code == 408 -> SubmitResult.Retry("ListenBrainz answered ${response.code}")
        else -> SubmitResult.Rejected(parse(response)?.get("error")?.jsonPrimitive?.contentOrNull ?: "ListenBrainz answered ${response.code}")
    }

    private fun parse(response: HttpResponse): JsonObject? =
        runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()

    companion object {
        const val BASE_URL = "https://api.listenbrainz.org"
        const val PLAYING_NOW = "playing_now"
        const val SINGLE = "single"
        const val IMPORT = "import"
        private const val CLIENT = "Polyphony"
    }
}
