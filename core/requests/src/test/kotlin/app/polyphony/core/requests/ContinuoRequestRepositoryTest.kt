package app.polyphony.core.requests

import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.RequestAction
import app.polyphony.core.domain.model.RequestEvent
import app.polyphony.core.domain.model.RequestOutcome
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.ServerRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ContinuoRequestRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    private val server = Server(
        id = "s1", type = ServerType.SUBSONIC, name = "continuo", baseUrl = "https://music-api.example.com",
        username = "isaac", credential = "app-secret", deviceId = "d1",
        options = mapOf(ServerOptions.CONTINUO to "true", ServerOptions.CONTINUO_REFRESH_TOKEN to "crt_0"),
    )

    private class FakeServers(initial: Server) : ServerRepository {
        val state = MutableStateFlow(listOf(initial))
        override val servers: Flow<List<Server>> = state
        override val activeServer: Flow<Server?> = state.map { it.firstOrNull() }
        override suspend fun getActive(): Server? = state.value.firstOrNull()
        override suspend fun get(id: String): Server? = state.value.firstOrNull { it.id == id }
        override suspend fun add(server: Server, makeActive: Boolean) { state.value = state.value + server }
        override suspend fun update(server: Server) { state.value = state.value.map { if (it.id == server.id) server else it } }
        override suspend fun remove(serverId: String) { state.value = state.value.filterNot { it.id == serverId } }
        override suspend fun setActive(serverId: String) = Unit
    }

    /** A fake continuo: rotates refresh tokens like the real one and refuses a reused one. */
    private class FakeContinuo {
        var issued = 0
        val current = AtomicInteger(0)
        val refreshes = AtomicInteger(0)
        var expireNextAccess = false
        val routes = mutableMapOf<String, (Request, String?) -> Pair<Int, String>>()
        val seen = mutableListOf<Pair<Request, String?>>()

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            synchronized(this) { seen += req to body }
            val (code, text) = if (req.url.encodedPath == "/v1/auth/refresh") {
                refreshes.incrementAndGet()
                val presented = Regex("crt_(\\d+)").find(body.orEmpty())?.groupValues?.get(1)?.toInt()
                synchronized(this) {
                    if (presented != current.get()) 401 to """{"error":{"code":"refresh_token_reused","message":"revoked"}}"""
                    else {
                        current.incrementAndGet(); issued++
                        200 to """{"accessToken":"at_$issued","expiresIn":3600,"refreshToken":"crt_${current.get()}"}"""
                    }
                }
            } else {
                val auth = req.header("Authorization")
                if (expireNextAccess) { expireNextAccess = false; 401 to "{}" }
                else if (auth?.startsWith("Bearer at_") != true) 401 to "{}"
                else routes["${req.method} ${req.url.encodedPath}"]?.invoke(req, body) ?: (404 to "{}")
            }
            val type = if (req.url.encodedPath == "/v1/events") "text/event-stream" else "application/json"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(text.toResponseBody(type.toMediaType())).build()
        }).build()
    }

    private fun repo(fake: FakeContinuo, servers: FakeServers) =
        ContinuoRequestRepository(ContinuoClient({ fake.client }, json, servers))

    private val searchBody = """{"query":"geogaddi","sources":{"library":"ok","catalog":"ok"},"results":[
      {"kind":"album","key":"mb:rg:geo","title":"Geogaddi","artist":"Boards of Canada","year":2002,
       "artwork":{"url":"https://music-api.example.com/rest/getCoverArt?id=al-1","auth":"subsonic"},
       "ids":{"musicBrainzReleaseGroupId":"geo","navidromeAlbumId":"nd-1"},"availability":{"state":"in_library","navidromeAlbumId":"nd-1","trackCount":23}},
      {"kind":"album","key":"mb:rg:th","title":"Tomorrow's Harvest","artist":"Boards of Canada",
       "artwork":{"url":"https://coverartarchive.org/release-group/th/front-500"},
       "ids":{"musicBrainzReleaseGroupId":"th"},"availability":{"state":"requestable"}},
      {"kind":"album","key":"mb:rg:mhtrtc","title":"Music Has the Right to Children","artist":"Boards of Canada",
       "ids":{"musicBrainzReleaseGroupId":"mhtrtc"},"availability":{"state":"downloading","requestId":17,"percent":42.0,"source":"soulseek"}},
      {"kind":"album","key":"mb:rg:x","title":"Future Thing","availability":{"state":"teleporting","requestId":3}},
      {"kind":"artist","key":"mb:artist:boc","name":"Boards of Canada","availability":{"state":"in_library"}},
      {"kind":"track","key":"mb:rec:1","title":"Dayvan Cowboy","artist":"Boards of Canada","durationMs":300000,
       "album":{"title":"Trans Canada Highway","musicBrainzReleaseGroupId":"tch","navidromeAlbumId":null},
       "availability":{"state":"requestable","via":"album"}}
    ]}"""

    @Test
    fun `search maps availability and drops third-party artwork and artists`() = runTest {
        val fake = FakeContinuo().apply { routes["GET /v1/search"] = { _, _ -> 200 to searchBody } }
        val results = repo(fake, FakeServers(server)).search(server, "geogaddi")

        assertEquals(listOf("Geogaddi", "Tomorrow's Harvest", "Music Has the Right to Children", "Future Thing"), results.albums.map { it.title })
        assertEquals(Availability.InLibrary("nd-1"), results.albums[0].availability)
        assertEquals("https://music-api.example.com/rest/getCoverArt?id=al-1", results.albums[0].artworkUrl)
        assertEquals(Availability.Requestable, results.albums[1].availability)
        assertNull("coverartarchive.org must not be contacted", results.albums[1].artworkUrl)
        assertEquals(Availability.Requested(17, "downloading", false), results.albums[2].availability)
        assertEquals(Availability.Requested(3, "teleporting", false), results.albums[3].availability)
        assertEquals("tch", results.tracks.single().albumReleaseGroupId)
        assertTrue(results.catalogAvailable)

        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/search" }.first.url
        assertEquals("geogaddi", sent.queryParameter("q"))
        assertEquals("album,track", sent.queryParameter("types"))
    }

    @Test
    fun `refresh rotates the stored token and the access token is reused`() = runTest {
        val servers = FakeServers(server)
        val fake = FakeContinuo().apply { routes["GET /v1/search"] = { _, _ -> 200 to """{"results":[]}""" } }
        val repo = repo(fake, servers)
        repo.search(server, "a")
        repo.search(server, "b")
        assertEquals(1, fake.refreshes.get())
        assertEquals("crt_1", servers.get("s1")!!.options[ServerOptions.CONTINUO_REFRESH_TOKEN])
    }

    @Test
    fun `a refused access token is refreshed once and retried`() = runTest {
        val servers = FakeServers(server)
        val fake = FakeContinuo().apply { routes["GET /v1/search"] = { _, _ -> 200 to """{"results":[]}""" } }
        val repo = repo(fake, servers)
        repo.search(server, "a")
        fake.expireNextAccess = true
        repo.search(server, "b")
        assertEquals(2, fake.refreshes.get())
        assertEquals("crt_2", servers.get("s1")!!.options[ServerOptions.CONTINUO_REFRESH_TOKEN])
    }

    @Test
    fun `concurrent first calls refresh once, never presenting a rotated token`() = runTest {
        val servers = FakeServers(server)
        val fake = FakeContinuo().apply { routes["GET /v1/search"] = { _, _ -> 200 to """{"results":[]}""" } }
        val repo = repo(fake, servers)
        (1..8).map { async { repo.search(server, "q$it") } }.awaitAll()
        assertEquals(1, fake.refreshes.get())
    }

    @Test
    fun `a revoked device asks for a new sign-in`() = runTest {
        val revoked = server.copy(options = server.options + (ServerOptions.CONTINUO_REFRESH_TOKEN to "crt_9"))
        val fake = FakeContinuo()
        try {
            repo(fake, FakeServers(revoked)).search(revoked, "a")
            fail("expected Unauthorized")
        } catch (e: ProviderException.Unauthorized) {
            assertTrue(e.message!!.contains("sign in", ignoreCase = true))
        }
    }

    @Test
    fun `request outcomes`() = runTest {
        val fake = FakeContinuo()
        val repo = repo(fake, FakeServers(server))
        fake.routes["POST /v1/requests"] = { _, _ -> 201 to """{"id":21,"status":"pending"}""" }
        assertEquals(RequestOutcome.Requested(21), repo.requestAlbum(server, "th"))
        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/requests" }.second!!
        assertTrue(sent.contains("\"musicBrainzReleaseGroupId\":\"th\"") && sent.contains("\"kind\":\"album\""))

        fake.routes["POST /v1/requests"] = { _, _ ->
            409 to """{"error":{"code":"already_requested","message":"x","retryable":false,"requestId":21}}"""
        }
        assertEquals(RequestOutcome.AlreadyRequested(21), repo.requestAlbum(server, "th"))

        fake.routes["POST /v1/requests"] = { _, _ ->
            409 to """{"error":{"code":"in_library","message":"x","retryable":false,"navidromeAlbumId":"nd-9"}}"""
        }
        assertEquals(RequestOutcome.InLibrary("nd-9"), repo.requestAlbum(server, "th"))

        fake.routes["POST /v1/requests"] = { _, _ -> 502 to """{"error":{"code":"lidarr_unavailable","message":"Lidarr is down."}}""" }
        try {
            repo.requestAlbum(server, "th"); fail("expected Server")
        } catch (e: ProviderException.Server) {
            assertEquals(502, e.code); assertEquals("Lidarr is down.", e.message)
        }
    }

    @Test
    fun `a server without a stored session can't search`() = runTest {
        val bare = server.copy(options = mapOf(ServerOptions.CONTINUO to "true"))
        try {
            repo(FakeContinuo(), FakeServers(bare)).search(bare, "a"); fail("expected Unauthorized")
        } catch (_: ProviderException.Unauthorized) {
        }
    }

    private val listBody = """{"items":[
      {"id":1,"kind":"album","title":"Honeycomb","artist":"Jitwam","year":2019,
       "artwork":{"url":"https://coverartarchive.org/x"},"ids":{"musicBrainzReleaseGroupId":"rg-h","navidromeAlbumId":"nd-h"},
       "status":"available","stillLooking":false,
       "pipeline":{"stages":["requested","searching","downloading","importing","scanning","available"],"stage":"available","stageIndex":5,"percent":100,"detail":"In your library — ready to play."},
       "play":{"navidromeAlbumId":"nd-h","deepLink":"polyphony://album/music-api.example.com/nd-h"},"requestedBy":"isaac"},
      {"id":2,"title":"Selftitled","artist":"Jitwam","status":"downloading","stillLooking":false,
       "pipeline":{"stageIndex":2,"percent":42.5,"detail":"Downloading via qBittorrent","source":"torrent",
                   "download":{"client":"qBittorrent","stalled":true,"queueId":7}}},
      {"id":3,"title":"Third","status":"searching","stillLooking":true,
       "pipeline":{"stageIndex":1,"detail":"Still looking — 14 search cycles"},"error":null}
    ],"cursor":null}"""

    @Test
    fun `requests are listed with their pipelines`() = runTest {
        val fake = FakeContinuo().apply { routes["GET /v1/requests"] = { _, _ -> 200 to listBody } }
        val list = repo(fake, FakeServers(server)).requests(server, activeOnly = true)
        assertEquals(listOf(1L, 2L, 3L), list.map { it.id })
        assertEquals("nd-h", list[0].libraryAlbumId)
        assertNull(list[0].artworkUrl)
        assertEquals(5, list[0].pipeline!!.stageIndex)
        val dl = list[1].pipeline!!
        assertTrue(dl.hasDownload && dl.stalled && dl.source == "torrent" && dl.percent == 42.5f)
        assertTrue(list[2].stillLooking && list[2].libraryAlbumId == null)
        assertEquals("active", fake.seen.last { it.first.url.encodedPath == "/v1/requests" }.first.url.queryParameter("scope"))
    }

    @Test
    fun `actions hit the right endpoints and return continuo's message`() = runTest {
        val fake = FakeContinuo().apply {
            routes["POST /v1/requests/7/search-now"] = { _, _ -> 200 to """{"ok":true,"message":"Searching now — this can take a minute."}""" }
            routes["POST /v1/requests/7/retry"] = { _, _ -> 200 to """{"ok":true,"message":"Retrying — searching again now."}""" }
            routes["POST /v1/requests/7/drop-download"] = { _, _ -> 409 to """{"error":{"code":"nothing_to_drop","message":"Nothing is downloading for this request."}}""" }
            routes["DELETE /v1/requests/7"] = { _, _ -> 200 to """{"ok":true,"message":"Request removed."}""" }
        }
        val repo = repo(fake, FakeServers(server))
        assertEquals("Searching now — this can take a minute.", repo.perform(server, 7, RequestAction.SEARCH_NOW))
        assertEquals("Retrying — searching again now.", repo.perform(server, 7, RequestAction.RETRY))
        assertEquals("Request removed.", repo.perform(server, 7, RequestAction.REMOVE))
        try {
            repo.perform(server, 7, RequestAction.DROP_DOWNLOAD); fail("expected Server")
        } catch (e: ProviderException.Server) {
            assertEquals(409, e.code); assertEquals("Nothing is downloading for this request.", e.message)
        }
    }

    @Test
    fun `event stream delivers updates and asks for a resync after reconnecting`() = runTest {
        val fake = FakeContinuo().apply {
            routes["GET /v1/events"] = { _, _ ->
                200 to "event: hello\ndata: {\"user\":\"isaac\"}\n\n" +
                    ": ping\n\n" +
                    "event: request.updated\ndata: {\"id\":2,\"status\":\"importing\",\"stillLooking\":false," +
                    "\"pipeline\":{\"stageIndex\":3,\"detail\":\"Importing into your library…\"}}\n\n" +
                    "event: notification.new\ndata: {\"id\":9}\n\n"
            }
        }
        val events = repo(fake, FakeServers(server)).events(server).take(2).toList()
        val updated = events[0] as RequestEvent.Updated
        assertEquals(2L, updated.id)
        assertEquals("importing", updated.status)
        assertEquals(3, updated.pipeline!!.stageIndex)
        // The stream ended (server closed it); after the reconnect's hello the app must reload.
        assertEquals(RequestEvent.Resync, events[1])
        val auth = fake.seen.filter { it.first.url.encodedPath == "/v1/events" }.map { it.first.header("Authorization") }
        assertTrue(auth.size >= 2 && auth.all { it == "Bearer at_1" })
    }
}
