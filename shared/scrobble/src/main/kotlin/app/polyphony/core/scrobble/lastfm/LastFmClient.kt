package app.polyphony.core.scrobble.lastfm

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder

/** The app's Last.fm API account; [available] only when this build was given one (see shared/scrobble/build.gradle.kts). */
data class LastFmConfig(val apiKey: String, val secret: String) {
    val available: Boolean get() = apiKey.isNotBlank() && secret.isNotBlank()

    override fun toString(): String = "LastFmConfig(available=$available)"
}

/**
 * Last.fm's Scrobbling API 2.0 (https://www.last.fm/api/scrobbling): every call is a signed POST
 * ([LastFmSignature]) to one endpoint. A session key comes from `auth.getMobileSession`; listens go
 * to `track.updateNowPlaying` and, up to 50 at a time, `track.scrobble`.
 */
class LastFmClient(
    private val http: HttpTransport,
    private val config: LastFmConfig,
    private val endpoint: String = ENDPOINT,
) : ScrobbleClient {

    override val service = ScrobbleService.LASTFM
    override val batchLimit = 50

    /** Last.fm asks clients not to scrobble tracks of 30 seconds or less (unknown lengths go through). */
    override fun accepts(scrobble: Scrobble): Boolean = scrobble.durationMs <= 0 || scrobble.durationMs > MIN_TRACK_MS

    /** Signs in: the session (user name, key) for [username]. Throws [ScrobbleAuthException] when refused. */
    suspend fun mobileSession(username: String, password: String): Pair<String, String> {
        val response = http.execute(request(mapOf("method" to "auth.getMobileSession", "username" to username.trim(), "password" to password)))
        val body = parse(response)
        val error = body?.errorCode()
        if (error != null || response.code !in 200..299) {
            if (error != null && error !in RETRYABLE) throw ScrobbleAuthException(body?.message() ?: "Last.fm refused the sign-in")
            throw IOException(body?.message() ?: "Last.fm answered ${response.code}")
        }
        val session = body?.get("session")?.jsonObject ?: throw IOException("Last.fm sent no session")
        val name = session["name"]?.jsonPrimitive?.contentOrNull ?: username.trim()
        val key = session["key"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Last.fm sent no session key")
        return name to key
    }

    override suspend fun nowPlaying(account: ScrobbleAccount, scrobble: Scrobble) {
        http.execute(request(nowPlayingParams(account.secret, scrobble)))
    }

    override suspend fun submit(account: ScrobbleAccount, batch: List<Scrobble>): SubmitResult {
        if (batch.isEmpty()) return SubmitResult.Accepted()
        require(batch.size <= batchLimit) { "Last.fm takes at most $batchLimit scrobbles at once" }
        return result(http.execute(request(scrobbleParams(account.secret, batch))), batch.size)
    }

    fun nowPlayingParams(sessionKey: String, s: Scrobble): Map<String, String> = buildMap {
        put("method", "track.updateNowPlaying")
        put("sk", sessionKey)
        putTrack(s, suffix = "")
    }

    /** `track.scrobble` with each listen's fields indexed: artist[0], track[0], timestamp[0]... */
    fun scrobbleParams(sessionKey: String, batch: List<Scrobble>): Map<String, String> = buildMap {
        put("method", "track.scrobble")
        put("sk", sessionKey)
        batch.forEachIndexed { i, s ->
            putTrack(s, suffix = "[$i]")
            put("timestamp[$i]", s.listenedAtSeconds.toString())
        }
    }

    private fun MutableMap<String, String>.putTrack(s: Scrobble, suffix: String) {
        put("artist$suffix", s.artist)
        put("track$suffix", s.track)
        s.album?.let { put("album$suffix", it) }
        s.albumArtist?.let { put("albumArtist$suffix", it) }
        if (s.durationMs > 0) put("duration$suffix", (s.durationMs / 1000).toString())
        s.trackNumber?.let { put("trackNumber$suffix", it.toString()) }
        s.recordingMbid?.let { put("mbid$suffix", it) }
    }

    /** The signed form for [params]: api_key added, api_sig over everything, format=json unsigned. */
    fun signedForm(params: Map<String, String>): Map<String, String> {
        val withKey = params + ("api_key" to config.apiKey)
        return withKey + ("api_sig" to LastFmSignature.sign(withKey, config.secret)) + ("format" to "json")
    }

    private fun request(params: Map<String, String>) = HttpRequest(
        method = "POST",
        url = endpoint,
        body = signedForm(params).entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" },
        contentType = "application/x-www-form-urlencoded; charset=utf-8",
    )

    private fun result(response: HttpResponse, size: Int): SubmitResult {
        val body = parse(response)
        val error = body?.errorCode()
        if (error != null) {
            val reason = "Last.fm error $error: ${body?.message().orEmpty()}"
            return when (error) {
                in AUTH -> SubmitResult.Unauthorized
                // Only this says the listen itself is wrong; the drainer then drops just that one.
                INVALID_PARAMETERS -> SubmitResult.Rejected(reason)
                // Anything else is the service (8, 11, 16, 29) or this build (a wrong API secret
                // gives 13 on every request): the plays wait, backing off, rather than all being
                // dropped one by one.
                else -> SubmitResult.Retry(reason)
            }
        }
        if (response.code >= 500 || response.code == 429 || response.code == 408) return SubmitResult.Retry("Last.fm answered ${response.code}")
        if (response.code !in 200..299) return SubmitResult.Rejected("Last.fm answered ${response.code}")
        val scrobbles = body?.get("scrobbles")?.jsonObject ?: return SubmitResult.Retry("Last.fm sent an unreadable answer")
        // One scrobble comes back as an object, several as an array.
        val entries: List<JsonElement> = when (val s = scrobbles["scrobble"]) {
            is JsonArray -> s
            is JsonObject -> listOf(s)
            else -> emptyList()
        }
        val ignored = mutableSetOf<Int>()
        val deferred = mutableSetOf<Int>()
        entries.take(size).forEachIndexed { i, entry ->
            val code = (entry as? JsonObject)?.get("ignoredMessage")?.let { it as? JsonObject }?.get("code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            when (code) {
                0 -> Unit
                IGNORED_DAILY_LIMIT -> deferred += i
                else -> ignored += i
            }
        }
        return SubmitResult.Accepted(ignored = ignored, deferred = deferred)
    }

    private fun parse(response: HttpResponse): JsonObject? =
        runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()

    private fun JsonObject.errorCode(): Int? = get("error")?.jsonPrimitive?.intOrNull
    private fun JsonObject.message(): String? = get("message")?.jsonPrimitive?.contentOrNull

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
        private const val MIN_TRACK_MS = 30_000L

        /** Authentication failed (4), invalid session key (9), invalid or suspended API key (10, 26), unauthorised token (14). */
        private val AUTH = setOf(4, 9, 10, 14, 26)

        /** Operation failed (8), service offline (11), temporarily unavailable (16), rate limit (29): a sign-in to try again. */
        private val RETRYABLE = setOf(8, 11, 16, 29)

        /** Invalid parameters: the one Last.fm error about the listen rather than the service or the app. */
        private const val INVALID_PARAMETERS = 6

        /** A scrobble's ignoredMessage code for "daily scrobble limit exceeded": send it again later. */
        private const val IGNORED_DAILY_LIMIT = 5
    }
}
