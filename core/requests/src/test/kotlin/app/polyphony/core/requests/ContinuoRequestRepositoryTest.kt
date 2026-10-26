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
import kotlinx.coroutines.runBlocking
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
      {"kind":"artist","key":"mb:artist:boc","name":"Boards of Canada","ids":{"musicBrainzArtistId":"boc","navidromeArtistId":"ar-1"},"availability":{"state":"in_library"}},
      {"kind":"artist","key":"mb:artist:ae","name":"Autechre","artwork":{"url":"https://music-api.example.com/v1/artwork?src=x"},"ids":{"musicBrainzArtistId":"ae"},"availability":{"state":"catalog"}},
      {"kind":"track","key":"mb:rec:1","title":"Dayvan Cowboy","artist":"Boards of Canada","durationMs":300000,
       "album":{"title":"Trans Canada Highway","musicBrainzReleaseGroupId":"tch","navidromeAlbumId":null},
       "availability":{"state":"requestable","via":"album"}}
    ]}"""

    @Test
    fun `search maps availability and drops third-party artwork`() = runTest {
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
        assertEquals("the recording, for requesting just the song", "1", results.tracks.single().recordingId)
        assertEquals(listOf("boc", "ae"), results.artists.map { it.musicBrainzId })
        assertEquals(Availability.CatalogOnly, results.artists[1].availability)
        assertEquals("https://music-api.example.com/v1/artwork?src=x", results.artists[1].artworkUrl)
        assertTrue(results.catalogAvailable)

        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/search" }.first.url
        assertEquals("geogaddi", sent.queryParameter("q"))
        assertEquals("album,artist,track", sent.queryParameter("types"))
    }

    @Test
    fun `a catalogue album comes with its tracklist`() = runTest {
        val body = """{"kind":"album","key":"mb:rg:th","title":"Tomorrow's Harvest","artist":"Boards of Canada","year":2013,"albumType":"Album",
          "ids":{"musicBrainzReleaseGroupId":"th"},"availability":{"state":"requestable"},
          "tracks":[{"position":1,"title":"Gemini","durationMs":172000,"discNumber":1,"musicBrainzRecordingId":"r1"},
                    {"position":2,"title":"Reach for the Dead","durationMs":null,"discNumber":1}]}"""
        val fake = FakeContinuo().apply { routes["GET /v1/catalog/albums/th"] = { _, _ -> 200 to body } }
        val detail = repo(fake, FakeServers(server)).catalogAlbum(server, "th")

        assertEquals("Tomorrow's Harvest", detail.album.title)
        assertEquals(Availability.Requestable, detail.album.availability)
        assertEquals(listOf("Gemini", "Reach for the Dead"), detail.tracks.map { it.title })
        assertEquals(172000L, detail.tracks[0].durationMs)
        assertNull(detail.tracks[1].durationMs)
    }

    @Test
    fun `an artist page maps picture, biography, top songs and similar artists`() = runTest {
        val body = """{"kind":"artist","key":"mb:artist:boc","name":"Boards of Canada","disambiguation":"",
          "artwork":{"url":"https://music-api.example.com/v1/artwork?src=p"},
          "biography":{"text":"A Scottish duo.","source":"wikipedia","url":"https://en.wikipedia.org/wiki/Boards_of_Canada"},
          "ids":{"musicBrainzArtistId":"boc"},"availability":{"state":"catalog"},
          "topTracks":[{"kind":"track","key":"mb:rec:r1","title":"Dayvan Cowboy","durationMs":300000,
            "album":{"title":"Trans Canada Highway","musicBrainzReleaseGroupId":"tch","navidromeAlbumId":null},
            "availability":{"state":"requestable","via":"album"}}],
          "similarArtists":[{"kind":"artist","key":"mb:artist:ae","name":"Aphex Twin","artwork":{"url":null},
            "ids":{"musicBrainzArtistId":"ae"},"availability":{"state":"in_library"}}],
          "sources":{"topTracks":"ok","similarArtists":"ok"}}"""
        val fake = FakeContinuo().apply {
            routes["GET /v1/catalog/artists/boc"] = { _, _ -> 200 to body }
            routes["GET /v1/catalog/artists/gone"] = { _, _ -> 404 to """{"error":{"code":"not_found","message":"Artist not found in the catalogue."}}""" }
        }
        val repo = repo(fake, FakeServers(server))
        val page = repo.catalogArtist(server, "boc")!!

        assertEquals("Boards of Canada", page.artist.name)
        assertEquals("https://music-api.example.com/v1/artwork?src=p", page.artist.artworkUrl)
        assertNull(page.disambiguation)
        assertEquals("A Scottish duo.", page.biography)
        assertEquals("tch", page.topTracks.single().albumReleaseGroupId)
        assertEquals(Availability.Requestable, page.topTracks.single().availability)
        assertEquals(Availability.InLibrary(null), page.similarArtists.single().availability)
        assertNull("an unknown artist (or an older continuo) is no page, not an error", repo.catalogArtist(server, "gone"))
    }

    @Test
    fun `whoever the server offers removal to can remove albums`() = runTest {
        val fake = FakeContinuo().apply {
            routes["GET /v1/me"] = { _, _ -> 200 to """{"user":{"id":1,"username":"isaac","role":"admin"},"capabilities":["requests","library_removal"]}""" }
        }
        val account = repo(fake, FakeServers(server)).account(server)
        assertEquals("isaac", account.username)
        assertTrue(account.canRemoveAlbums)
        assertTrue("not just admins", account.copy(role = "user").canRemoveAlbums)
        assertEquals(false, account.copy(capabilities = setOf("requests")).canRemoveAlbums)
        assertTrue(account.copy(capabilities = setOf("track_requests")).canRequestSongs)
        assertEquals(false, account.canRequestSongs)
    }

    @Test
    fun `removing, listing and restoring albums`() = runTest {
        val removed = """{"id":4,"kind":"album","title":"0","artist":"Ichiko Aoba","removedAt":"2026-10-23T10:00:00+00:00",
          "removedBy":"isaac","restorableUntil":"2026-11-22T10:00:00+00:00","bytes":10,"ids":{"navidromeAlbumId":"nd-1"}}"""
        val fake = FakeContinuo().apply {
            routes["POST /v1/library/albums/nd-1/remove"] = { _, _ -> 200 to removed }
            routes["GET /v1/library/removed"] = { _, _ -> 200 to "[$removed]" }
            routes["POST /v1/library/removed/4/restore"] = { _, _ -> 200 to """{"restored":true}""" }
            routes["POST /v1/library/albums/nd-2/remove"] = { _, _ ->
                409 to """{"error":{"code":"shared_folder","message":"Another album shares this folder, so it can't be removed on its own."}}"""
            }
        }
        val repo = repo(fake, FakeServers(server))
        val out = repo.removeAlbum(server, "nd-1")
        assertEquals(4L, out.id)
        assertEquals("nd-1", out.libraryAlbumId)
        assertTrue((out.restorableUntilMillis ?: 0) > (out.removedAtMillis ?: Long.MAX_VALUE))
        assertEquals(listOf("0"), repo.removedAlbums(server).map { it.title })
        repo.restoreAlbum(server, 4)
        assertTrue(fake.seen.any { it.first.url.encodedPath == "/v1/library/removed/4/restore" })
        try {
            repo.removeAlbum(server, "nd-2")
            fail("a refusal must surface")
        } catch (e: ProviderException) {
            assertTrue(e.message.orEmpty().contains("shares this folder"))
        }
    }

    @Test
    fun `continuo's resolver picks among same-name artists`() = runTest {
        val fake = FakeContinuo().apply {
            routes["GET /v1/catalog/artists/resolve"] = { req, _ ->
                assertEquals("Peach Pit", req.url.queryParameter("name"))
                200 to """{"musicBrainzArtistId":"canadian","name":"Peach Pit","sharedAlbums":1,"inLidarr":false}"""
            }
        }
        assertEquals("canadian", repo(fake, FakeServers(server)).findArtist(server, "Peach Pit")?.musicBrainzId)
        assertTrue("no name search once the resolver answered", fake.seen.none { it.first.url.encodedPath == "/v1/search" })
    }

    @Test
    fun `an artist without an id is found by name in the catalogue`() = runTest {
        val fake = FakeContinuo().apply {
            routes["GET /v1/search"] = { _, _ ->
                200 to """{"results":[
                  {"kind":"artist","key":"mb:artist:x","name":"Boards of Canadaa","ids":{"musicBrainzArtistId":"x"},"availability":{"state":"catalog"}},
                  {"kind":"artist","key":"nd:artist:ar-1","name":"Boards Of Canada","ids":{"navidromeArtistId":"ar-1"},"availability":{"state":"in_library"}},
                  {"kind":"artist","key":"mb:artist:boc","name":"Boards of Canada","ids":{"musicBrainzArtistId":"boc"},"availability":{"state":"in_library"}}
                ]}"""
            }
        }
        val repo = repo(fake, FakeServers(server))

        assertEquals("boc", repo.findArtist(server, "boards of canada")?.musicBrainzId)
        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/search" }.first.url
        assertEquals("artist", sent.queryParameter("types"))
        assertNull(repo.findArtist(server, "Autechre"))
    }

    @Test
    fun `an artist's discography maps each album's availability`() = runTest {
        val body = """[
          {"kind":"album","key":"mb:rg:geo","title":"Geogaddi","artist":"Boards of Canada","year":2002,"albumType":"Album",
           "ids":{"musicBrainzReleaseGroupId":"geo","navidromeAlbumId":"nd-1"},"availability":{"state":"in_library","navidromeAlbumId":"nd-1"}},
          {"kind":"album","key":"mb:rg:in","title":"In a Beautiful Place Out in the Country","year":2000,"albumType":"EP",
           "artwork":{"url":"https://music-api.example.com/v1/artwork?src=y"},
           "ids":{"musicBrainzReleaseGroupId":"in"},"availability":{"state":"requestable"}},
          {"kind":"album","key":"mb:rg:mh","title":"Music Has the Right to Children","year":1998,"albumType":"Album",
           "ids":{"musicBrainzReleaseGroupId":"mh"},"availability":{"state":"on_server","storeAlbumId":"s-mh","trackCount":18}},
          {"kind":"album","key":"mb:rg:bsn","title":"Being So Normal","year":2017,"albumType":"Album",
           "ids":{"musicBrainzReleaseGroupId":"bsn","navidromeAlbumId":"nd-s"},
           "availability":{"state":"partial","have":1,"expected":10,"navidromeAlbumId":"nd-s","requestable":true}}
        ]"""
        val fake = FakeContinuo().apply { routes["GET /v1/catalog/artists/boc/albums"] = { _, _ -> 200 to body } }
        val albums = repo(fake, FakeServers(server)).artistAlbums(server, "boc")

        assertEquals(listOf("geo", "in", "mh", "bsn"), albums.map { it.releaseGroupId })
        assertEquals(Availability.Partial(1, 10, "nd-s", requestable = true), albums[3].availability)
        assertEquals(Availability.OnServer("s-mh"), albums[2].availability)
        assertEquals(Availability.InLibrary("nd-1"), albums[0].availability)
        assertEquals("EP", albums[1].albumType)
        assertEquals(Availability.Requestable, albums[1].availability)
        assertEquals("https://music-api.example.com/v1/artwork?src=y", albums[1].artworkUrl)
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

        fake.routes["POST /v1/requests"] = { _, _ ->
            409 to """{"error":{"code":"added_to_library","message":"x","retryable":false,"storeAlbumId":"s-9"}}"""
        }
        assertEquals(RequestOutcome.Added, repo.requestAlbum(server, "th"))

        fake.routes["POST /v1/requests"] = { _, _ -> 201 to """{"id":22,"status":"pending"}""" }
        assertEquals(RequestOutcome.Requested(22), repo.requestTrack(server, "rec-1", "th"))
        val song = fake.seen.last { it.first.url.encodedPath == "/v1/requests" }.second!!
        assertTrue(song.contains("\"kind\":\"track\"") && song.contains("\"musicBrainzRecordingId\":\"rec-1\"") &&
            song.contains("\"musicBrainzReleaseGroupId\":\"th\""))

        fake.routes["POST /v1/requests"] = { _, _ -> 502 to """{"error":{"code":"lidarr_unavailable","message":"Lidarr is down."}}""" }
        try {
            repo.requestAlbum(server, "th"); fail("expected Server")
        } catch (e: ProviderException.Server) {
            assertEquals(502, e.code); assertEquals("Lidarr is down.", e.message)
        }
    }

    @Test
    fun `play now starts, reports when playable, keeps, and streams with the session`() = runTest {
        val fake = FakeContinuo()
        val repo = repo(fake, FakeServers(server))
        fake.routes["POST /v1/play-now"] = { _, _ -> 201 to """{"id":"s1","status":"searching","playable":false,"percent":0,"title":"Visit Croatia","artist":"Alabaster dePlume"}""" }
        val started = repo.startPlayNow(server, "rec-1", "rg-1", "Visit Croatia", "Alabaster dePlume", 230_000)
        assertEquals("s1", started.id)
        assertEquals(false, started.playable)
        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/play-now" }.second!!
        assertTrue(sent.contains("\"musicBrainzRecordingId\":\"rec-1\"") && sent.contains("\"title\":\"Visit Croatia\""))

        fake.routes["GET /v1/play-now/s1"] = { _, _ -> 200 to """{"id":"s1","status":"streaming","playable":true,"percent":12.5,"title":"Visit Croatia","format":"flac"}""" }
        val status = repo.playNowStatus(server, "s1")
        assertTrue(status.playable)
        assertEquals("flac", status.format)

        fake.routes["POST /v1/play-now/s1/keep"] = { _, _ -> 200 to """{"id":9}""" }
        repo.keepPlayNow(server, "s1")

        val stream = repo.playNowStream(server, "s1")
        assertTrue(stream.url.endsWith("/v1/play-now/s1/audio"))
        assertTrue(stream.headers["Authorization"]!!.startsWith("Bearer "))
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

    // ---- devices (#119) and sign-out (#120) ----

    private val devicesBody = """[
      {"id":"dev_old","name":"Pixel 8","platform":"android","createdAt":"2026-03-01T10:00:00+00:00",
       "lastSeenAt":"2026-04-02T11:00:00+00:00","current":false},
      {"id":"dev_now","name":"Pixel 8","platform":"android","createdAt":"2026-10-20T09:00:00+00:00",
       "lastSeenAt":"2026-10-21T08:00:00+00:00","current":true},
      {"id":"dev_bare","name":"","platform":null,"createdAt":"garbage","lastSeenAt":null}
    ]"""

    @Test
    fun `devices are listed with this one marked`() = runTest {
        val fake = FakeContinuo().apply { routes["GET /v1/devices"] = { _, _ -> 200 to devicesBody } }
        val devices = repo(fake, FakeServers(server)).devices(server)

        assertEquals(listOf("dev_old", "dev_now", "dev_bare"), devices.map { it.id })
        assertEquals(listOf(false, true, false), devices.map { it.current })
        assertEquals("Pixel 8", devices[0].name)
        assertEquals("android", devices[0].platform)
        assertEquals(java.time.Instant.parse("2026-04-02T11:00:00Z").toEpochMilli(), devices[0].lastSeenAtMillis)
        assertEquals(java.time.Instant.parse("2026-03-01T10:00:00Z").toEpochMilli(), devices[0].createdAtMillis)
        // A device without a name is shown by its id; unparseable or missing times are left out.
        assertEquals("dev_bare", devices[2].name)
        assertNull(devices[2].platform)
        assertNull(devices[2].createdAtMillis)
        assertNull(devices[2].lastSeenAtMillis)
    }

    @Test
    fun `revoking a device and the others hit the right endpoints`() = runTest {
        val fake = FakeContinuo().apply {
            routes["DELETE /v1/devices/dev_old"] = { _, _ -> 200 to """{"ok":true}""" }
            routes["POST /v1/devices/revoke-others"] = { _, _ -> 200 to """{"ok":true,"revoked":2}""" }
        }
        val repo = repo(fake, FakeServers(server))
        repo.revokeDevice(server, "dev_old")
        assertEquals(2, repo.revokeOtherDevices(server))
        val paths = fake.seen.map { "${it.first.method} ${it.first.url.encodedPath}" }
        assertTrue("DELETE /v1/devices/dev_old" in paths && "POST /v1/devices/revoke-others" in paths)
        try {
            repo.revokeDevice(server, "dev_gone"); fail("expected NotFound")
        } catch (e: ProviderException.NotFound) {
        }
    }

    @Test
    fun `sign out uses the session already open`() = runBlocking {
        val servers = FakeServers(server)
        val fake = FakeContinuo().apply {
            routes["GET /v1/devices"] = { _, _ -> 200 to "[]" }
            routes["POST /v1/auth/logout"] = { _, _ -> 200 to """{"ok":true}""" }
        }
        val repo = repo(fake, servers)
        repo.devices(server)
        repo.signOut(server)
        val logout = fake.seen.single { it.first.url.encodedPath == "/v1/auth/logout" }.first
        assertEquals("Bearer at_1", logout.header("Authorization"))
        assertEquals(1, fake.refreshes.get())
    }

    @Test
    fun `sign out refreshes once and doesn't store the rotated token`() = runBlocking {
        val servers = FakeServers(server)
        val fake = FakeContinuo().apply { routes["POST /v1/auth/logout"] = { _, _ -> 200 to """{"ok":true}""" } }
        val repo = repo(fake, servers)
        // The server is removed while the sign-out runs: it must not come back.
        servers.remove(server.id)
        repo.signOut(server)
        assertEquals(1, fake.refreshes.get())
        assertEquals("Bearer at_1", fake.seen.single { it.first.url.encodedPath == "/v1/auth/logout" }.first.header("Authorization"))
        assertTrue(servers.state.value.isEmpty())
    }

    @Test
    fun `sign out skips a server without a session and swallows failures`() = runBlocking {
        val fake = FakeContinuo().apply { routes["POST /v1/auth/logout"] = { _, _ -> 500 to "{}" } }
        val restored = server.withoutContinuoSession()
        repo(fake, FakeServers(restored)).signOut(restored)
        assertTrue(fake.seen.isEmpty())

        val plain = server.copy(options = emptyMap())
        repo(fake, FakeServers(plain)).signOut(plain)
        assertTrue(fake.seen.isEmpty())

        // Refused refresh (already revoked) and a failing logout both end quietly.
        val revoked = server.copy(options = server.options + (ServerOptions.CONTINUO_REFRESH_TOKEN to "crt_9"))
        repo(fake, FakeServers(revoked)).signOut(revoked)
        repo(fake, FakeServers(server)).signOut(server)
        assertEquals(1, fake.seen.count { it.first.url.encodedPath == "/v1/auth/logout" })
    }

    @Test
    fun `sign out gives up after its timeout`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val fake = FakeContinuo().apply {
            routes["POST /v1/auth/logout"] = { _, _ -> release.await(10, java.util.concurrent.TimeUnit.SECONDS); 200 to "{}" }
        }
        val repo = ContinuoRequestRepository(ContinuoClient({ fake.client }, json, FakeServers(server)), signOutTimeoutMs = 200)
        val started = System.nanoTime()
        repo.signOut(server)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue("sign out took $tookMs ms", tookMs < 5_000)
    }

    private val notificationsBody = """{"items":[
      {"id":4,"type":"request_available","title":"Ready to play","body":"Honeycomb — Jitwam",
       "createdAt":"2026-10-21T08:30:00+00:00","readAt":null,"requestId":1,
       "action":{"type":"open_album","navidromeAlbumId":"nd-h","requestId":1,"deepLink":"polyphony://album/music-api.example.com/nd-h"},
       "deliveredVia":["sse","homeassistant"]},
      {"id":5,"type":"request_needs_attention","title":"A request needs a look","body":"Third: ambiguous",
       "createdAt":"2026-10-21T09:00:00+00:00","readAt":"2026-10-21T09:05:00+00:00","requestId":3,
       "action":{"type":"open_request","requestId":3,"deepLink":"polyphony://requests/3"},"deliveredVia":["sse"]},
      {"id":6,"type":"something_new","title":"Hello","body":"","createdAt":"garbage","readAt":null,"action":null,"requestId":null}
    ],"cursor":6,"unreadCount":2}"""

    @Test
    fun `notifications are listed with their actions`() = runTest {
        val fake = FakeContinuo().apply { routes["GET /v1/notifications"] = { _, _ -> 200 to notificationsBody } }
        val page = repo(fake, FakeServers(server)).notifications(server, since = 3, unreadOnly = true, limit = 50)

        assertEquals(listOf(4L, 5L, 6L), page.items.map { it.id })
        assertEquals(6L, page.cursor)
        assertEquals(2, page.unreadCount)
        val available = page.items[0]
        assertEquals("request_available", available.type)
        assertEquals("Honeycomb — Jitwam", available.body)
        assertEquals("polyphony://album/music-api.example.com/nd-h", available.deepLink)
        assertEquals("nd-h", available.libraryAlbumId)
        assertEquals(1L, available.requestId)
        assertEquals(java.time.Instant.parse("2026-10-21T08:30:00Z").toEpochMilli(), available.createdAtMillis)
        assertTrue(!available.read)
        assertEquals(setOf("homeassistant", "sse"), available.deliveredVia)
        assertTrue(available.deliveredByHomeAssistant)
        assertTrue(page.items[1].read)
        assertEquals("polyphony://requests/3", page.items[1].deepLink)
        assertTrue(!page.items[1].deliveredByHomeAssistant)
        val unknown = page.items[2]
        assertNull(unknown.deepLink)
        assertNull(unknown.body)
        assertNull(unknown.createdAtMillis)
        assertNull(unknown.requestId)
        // A server from before deliveredVia: not delivered, so the app still posts it.
        assertTrue(unknown.deliveredVia.isEmpty())
        assertTrue(!unknown.deliveredByHomeAssistant)

        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/notifications" }.first.url
        assertEquals("3", sent.queryParameter("since"))
        assertEquals("true", sent.queryParameter("unread"))
        assertEquals("50", sent.queryParameter("limit"))
        // Oldest first is continuo's default; order is only sent when asking for newest first.
        assertNull(sent.queryParameter("order"))
    }

    @Test
    fun `newest first asks for order=desc and the cursor is the highest id`() = runTest {
        val fake = FakeContinuo().apply {
            routes["GET /v1/notifications"] = { _, _ ->
                200 to """{"items":[{"id":9,"type":"t","deliveredVia":["homeassistant"]},{"id":7,"type":"t"}],"unreadCount":2}"""
            }
        }
        val page = repo(fake, FakeServers(server)).notifications(server, limit = 2, newestFirst = true)
        assertEquals(listOf(9L, 7L), page.items.map { it.id })
        // No cursor from the server: still the highest id, not the last one listed.
        assertEquals(9L, page.cursor)
        assertTrue(page.items[0].deliveredByHomeAssistant)
        val sent = fake.seen.last { it.first.url.encodedPath == "/v1/notifications" }.first.url
        assertEquals("desc", sent.queryParameter("order"))
        assertEquals("0", sent.queryParameter("since"))
    }

    @Test
    fun `deliveredVia is read leniently`() {
        fun parse(value: String) = deliveryChannels(json.parseToJsonElement(value))
        assertEquals(setOf("homeassistant", "sse"), parse("""["sse","HomeAssistant","sse"]"""))
        assertEquals(setOf("homeassistant"), parse("\"homeassistant\""))
        assertEquals(emptySet<String>(), parse("null"))
        assertEquals(emptySet<String>(), parse("""{"homeassistant":true}"""))
        assertEquals(setOf("sse"), parse("""[1,null,"","sse"]"""))
        assertEquals(emptySet<String>(), deliveryChannels(null))
        // A shape the app doesn't expect never fails the page.
        val dto = json.decodeFromString(NotificationListDto.serializer(), """{"items":[{"id":1,"deliveredVia":42}]}""")
        assertTrue(dto.toDomain(0).items.single().deliveredVia.isEmpty())
    }

    @Test
    fun `an empty page keeps the cursor where it was`() = runTest {
        val fake = FakeContinuo().apply {
            routes["GET /v1/notifications"] = { _, _ -> 200 to """{"items":[],"unreadCount":0}""" }
        }
        val page = repo(fake, FakeServers(server)).notifications(server, since = 12, limit = 900)
        assertTrue(page.items.isEmpty())
        assertEquals(12L, page.cursor)
        assertEquals("500", fake.seen.last { it.first.url.encodedPath == "/v1/notifications" }.first.url.queryParameter("limit"))
    }

    @Test
    fun `mark read sends ids or all and returns the unread count`() = runTest {
        val fake = FakeContinuo().apply {
            routes["POST /v1/notifications/read"] = { _, body ->
                200 to (if (body!!.contains("\"all\":true")) """{"ok":true,"marked":3,"unreadCount":0}"""
                else """{"ok":true,"marked":1,"unreadCount":4}""")
            }
        }
        val repo = repo(fake, FakeServers(server))
        assertEquals(4, repo.markRead(server, listOf(7, 7, 8)))
        assertEquals("""{"ids":[7,8]}""", fake.seen.last { it.first.url.encodedPath == "/v1/notifications/read" }.second)
        assertEquals(0, repo.markAllRead(server))
        assertEquals("""{"all":true}""", fake.seen.last { it.first.url.encodedPath == "/v1/notifications/read" }.second)
    }

    @Test
    fun `a failed mark read is reported`() = runTest {
        val fake = FakeContinuo().apply {
            routes["POST /v1/notifications/read"] = { _, _ -> 400 to """{"error":{"code":"bad_request","message":"Pass ids or all=true."}}""" }
        }
        try {
            repo(fake, FakeServers(server)).markRead(server, listOf(1)); fail("expected Server")
        } catch (e: ProviderException.Server) {
            assertEquals(400, e.code); assertEquals("Pass ids or all=true.", e.message)
        }
    }
}
