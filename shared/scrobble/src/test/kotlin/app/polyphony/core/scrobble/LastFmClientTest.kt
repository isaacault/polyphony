package app.polyphony.core.scrobble

import app.polyphony.core.domain.model.ScrobbleAccount
import app.polyphony.core.domain.model.ScrobbleService
import app.polyphony.core.domain.repository.ScrobbleAuthException
import app.polyphony.core.scrobble.lastfm.LastFmClient
import app.polyphony.core.scrobble.lastfm.LastFmConfig
import app.polyphony.core.scrobble.lastfm.LastFmSignature
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LastFmClientTest {
    private val config = LastFmConfig("key", "secret")
    private val account = ScrobbleAccount(ScrobbleService.LASTFM, "alice", "SESSION")
    private val daftPunk = Scrobble(
        artist = "Daft Punk", track = "One More Time", listenedAtMs = 1_700_000_000_000, album = "Discovery", durationMs = 320_000,
    )
    private val bjork = Scrobble(artist = "Björk", track = "Joga", listenedAtMs = 1_700_000_400_000)

    @Test
    fun `the signature is the md5 of the sorted fields and the secret, format left out`() {
        // md5("api_keykeymethodauth.getMobileSessionpasswordpässusernamealicesecret"), worked out apart.
        val params = mapOf(
            "username" to "alice", "password" to "päss", "method" to "auth.getMobileSession", "api_key" to "key", "format" to "json",
        )
        assertEquals("59245d680630a410761d29c5912625d1", LastFmSignature.sign(params, "secret"))
    }

    @Test
    fun `a batch scrobble indexes each listen's fields and signs them all`() {
        val client = LastFmClient(FakeHttp(), config)
        val form = client.signedForm(client.scrobbleParams("SESSION", listOf(daftPunk, bjork)))
        assertEquals("track.scrobble", form["method"])
        assertEquals("Daft Punk", form["artist[0]"])
        assertEquals("One More Time", form["track[0]"])
        assertEquals("Discovery", form["album[0]"])
        assertEquals("320", form["duration[0]"])
        assertEquals("1700000000", form["timestamp[0]"])
        assertEquals("Björk", form["artist[1]"])
        assertEquals("1700000400", form["timestamp[1]"])
        assertFalse("no album, no length: not sent", "album[1]" in form || "duration[1]" in form)
        assertEquals("json", form["format"])
        // Over album[0] api_key artist[0] artist[1] duration[0] method sk timestamp[0] timestamp[1] track[0] track[1].
        assertEquals("11945dd15e313830cd53560cc8b5b2ee", form["api_sig"])
    }

    @Test
    fun `now playing sends the track with the session key`() = runTest {
        val http = FakeHttp().apply { answer(200, """{"nowplaying":{}}""") }
        LastFmClient(http, config).nowPlaying(account, daftPunk.copy(recordingMbid = "mbid-1", trackNumber = 2))
        val fields = formFields(http.requests.single().body).toMap()
        assertEquals("track.updateNowPlaying", fields["method"])
        assertEquals("SESSION", fields["sk"])
        assertEquals("mbid-1", fields["mbid"])
        assertEquals("2", fields["trackNumber"])
        assertEquals(LastFmSignature.sign(fields, "secret"), fields["api_sig"])
        assertEquals("POST", http.requests.single().method)
        assertTrue(http.requests.single().contentType!!.startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `the mobile session keeps the name and key, and a refusal is an auth error`() = runTest {
        val http = FakeHttp().apply {
            answer(200, """{"session":{"name":"Alice","key":"abc123","subscriber":0}}""")
            answer(403, """{"error":4,"message":"Authentication Failed"}""")
            answer(503, """{"error":11,"message":"Service Offline"}""")
        }
        val client = LastFmClient(http, config)
        assertEquals("Alice" to "abc123", client.mobileSession("alice", "pw"))
        assertEquals("auth.getMobileSession", formFields(http.requests[0].body).toMap()["method"])
        try {
            client.mobileSession("alice", "wrong")
            fail()
        } catch (_: ScrobbleAuthException) {
        }
        try {
            client.mobileSession("alice", "pw")
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun `answers map to what the queue does`() = runTest {
        val http = FakeHttp()
        val client = LastFmClient(http, config)
        suspend fun answer(code: Int, body: String): SubmitResult {
            http.answer(code, body)
            return client.submit(account, listOf(daftPunk, bjork))
        }
        assertEquals(SubmitResult.Unauthorized, answer(403, """{"error":9,"message":"Invalid session key"}"""))
        assertTrue(answer(200, """{"error":29,"message":"Rate limit exceeded"}""") is SubmitResult.Retry)
        assertTrue(answer(503, "<html>down</html>") is SubmitResult.Retry)
        assertTrue(answer(400, """{"error":6,"message":"Invalid parameters"}""") is SubmitResult.Rejected)
        // A build with the wrong API secret: every request fails the same way, and no play is dropped for it.
        assertTrue(answer(403, """{"error":13,"message":"Invalid method signature supplied"}""") is SubmitResult.Retry)
        assertTrue(answer(400, """{"error":3,"message":"Invalid Method"}""") is SubmitResult.Retry)
        val accepted = answer(
            200,
            """{"scrobbles":{"scrobble":[
                {"track":{"#text":"One More Time"},"ignoredMessage":{"code":"0","#text":""}},
                {"track":{"#text":"Joga"},"ignoredMessage":{"code":"1","#text":"Artist was ignored"}}
            ],"@attr":{"accepted":1,"ignored":1}}}""",
        )
        assertEquals(SubmitResult.Accepted(ignored = setOf(1)), accepted)
        val daily = answer(
            200,
            """{"scrobbles":{"scrobble":[{"ignoredMessage":{"code":"0"}},{"ignoredMessage":{"code":"5"}}],"@attr":{"accepted":1,"ignored":1}}}""",
        )
        assertEquals("the daily limit keeps it for later", SubmitResult.Accepted(deferred = setOf(1)), daily)
    }

    @Test
    fun `a single scrobble comes back as an object`() = runTest {
        val http = FakeHttp().apply {
            answer(200, """{"scrobbles":{"scrobble":{"ignoredMessage":{"code":"3","#text":"Timestamp too old"}},"@attr":{"accepted":0,"ignored":1}}}""")
        }
        assertEquals(SubmitResult.Accepted(ignored = setOf(0)), LastFmClient(http, config).submit(account, listOf(daftPunk)))
    }

    @Test
    fun `tracks of thirty seconds or less are never scrobbled to Last fm`() {
        val client = LastFmClient(FakeHttp(), config)
        assertFalse(client.accepts(daftPunk.copy(durationMs = 30_000)))
        assertTrue(client.accepts(daftPunk.copy(durationMs = 31_000)))
        assertTrue("unknown length", client.accepts(daftPunk.copy(durationMs = 0)))
    }

    @Test
    fun `without an API key Last fm isn't available`() {
        assertFalse(LastFmConfig("", "").available)
        assertFalse(LastFmConfig("key", "").available)
        assertTrue(config.available)
        assertFalse("never prints the secret", config.toString().contains("secret"))
    }
}
