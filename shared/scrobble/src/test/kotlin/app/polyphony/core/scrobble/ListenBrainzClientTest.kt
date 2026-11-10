package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.repository.ScrobbleAuthException
import app.polyphony.core.scrobble.listenbrainz.ListenBrainzClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ListenBrainzClientTest {
    private val account = ScrobbleAccount(ScrobbleService.LISTENBRAINZ, "alice", "tok-123")
    private val listen = Scrobble(
        artist = "Daft Punk",
        track = "One More Time",
        listenedAtMs = 1_700_000_000_500,
        album = "Discovery",
        durationMs = 320_357,
        trackNumber = 1,
        discNumber = 1,
        recordingMbid = "9b5b8d7c-0c46-4d29-9a3b-8c8e0f1f6a11",
        releaseMbid = "48117b82-8d5c-4f2b-8c8a-6e9d2a0f9b22",
        artistMbids = listOf("056e4f3e-d505-4dad-8ec1-d04f521cbb56"),
    )

    @Test
    fun `a single listen carries its time, track, album, length and MusicBrainz ids`() = runTest {
        val http = FakeHttp().apply { answer(200, """{"status":"ok"}""") }
        val result = ListenBrainzClient(http, "1.2.3").submit(account, listOf(listen))
        assertEquals(SubmitResult.Accepted(), result)
        val request = http.requests.single()
        assertEquals("https://api.listenbrainz.org/1/submit-listens", request.url)
        assertEquals("Token tok-123", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("single", body["listen_type"]!!.jsonPrimitive.content)
        val entry = body["payload"]!!.jsonArray.single().jsonObject
        assertEquals(1_700_000_000L, entry["listened_at"]!!.jsonPrimitive.long)
        val meta = entry["track_metadata"]!!.jsonObject
        assertEquals("Daft Punk", meta["artist_name"]!!.jsonPrimitive.content)
        assertEquals("One More Time", meta["track_name"]!!.jsonPrimitive.content)
        assertEquals("Discovery", meta["release_name"]!!.jsonPrimitive.content)
        val info = meta["additional_info"]!!.jsonObject
        assertEquals(320_357L, info["duration_ms"]!!.jsonPrimitive.long)
        assertEquals(listen.recordingMbid, info["recording_mbid"]!!.jsonPrimitive.content)
        assertEquals(listen.releaseMbid, info["release_mbid"]!!.jsonPrimitive.content)
        assertEquals(listen.artistMbids, info["artist_mbids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Polyphony", info["submission_client"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", info["submission_client_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `several listens are an import, and playing now has no time`() {
        val client = ListenBrainzClient(FakeHttp(), "1")
        val bare = Scrobble(artist = "Björk", track = "Joga", listenedAtMs = 1_000)
        val import = client.payload(ListenBrainzClient.IMPORT, listOf(listen, bare))
        assertEquals(2, import["payload"]!!.jsonArray.size)
        val bareInfo = import["payload"]!!.jsonArray[1].jsonObject["track_metadata"]!!.jsonObject
        assertNull("no album: no release_name", bareInfo["release_name"])
        assertFalse("no ids: none sent", "recording_mbid" in bareInfo["additional_info"]!!.jsonObject)
        val now = client.payload(ListenBrainzClient.PLAYING_NOW, listOf(listen))
        assertEquals("playing_now", now["listen_type"]!!.jsonPrimitive.content)
        assertNull(now["payload"]!!.jsonArray.single().jsonObject["listened_at"])
    }

    @Test
    fun `submitting several uses import`() = runTest {
        val http = FakeHttp().apply { answer(200, """{"status":"ok"}""") }
        ListenBrainzClient(http, "1").submit(account, listOf(listen, listen.copy(listenedAtMs = 2_000_000)))
        val body = Json.parseToJsonElement(http.requests.single().body!!).jsonObject
        assertEquals("import", body["listen_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `answers map to what the queue does`() = runTest {
        val http = FakeHttp()
        val client = ListenBrainzClient(http, "1")
        suspend fun answer(code: Int, body: String = "{}", headers: Map<String, String> = emptyMap()): SubmitResult {
            http.answer(code, body, headers)
            return client.submit(account, listOf(listen))
        }
        assertEquals(SubmitResult.Unauthorized, answer(401, """{"code":401,"error":"Invalid authorization token."}"""))
        assertEquals(SubmitResult.Retry("ListenBrainz rate limit", afterMs = 7_000), answer(429, headers = mapOf("x-ratelimit-reset-in" to "7")))
        assertTrue(answer(502) is SubmitResult.Retry)
        assertEquals(SubmitResult.Rejected("Invalid JSON document submitted."), answer(400, """{"code":400,"error":"Invalid JSON document submitted."}"""))
    }

    @Test
    fun `validating the token gives the user name, or refuses`() = runTest {
        val http = FakeHttp().apply {
            answer(200, """{"code":200,"message":"Token valid.","valid":true,"user_name":"alice"}""")
            answer(200, """{"code":200,"message":"Token invalid.","valid":false}""")
        }
        val client = ListenBrainzClient(http, "1")
        assertEquals("alice", client.validateToken(" tok-123 "))
        assertEquals("Token tok-123", http.requests[0].headers["Authorization"])
        try {
            client.validateToken("nope")
            fail()
        } catch (_: ScrobbleAuthException) {
        }
    }
}
