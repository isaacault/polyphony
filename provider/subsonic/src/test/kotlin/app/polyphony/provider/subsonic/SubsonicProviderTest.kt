package app.polyphony.provider.subsonic

import app.polyphony.core.domain.model.AlbumListType
import app.polyphony.core.domain.model.Capability
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubsonicProviderTest {

    private val extensions = """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"transcodeOffset","versions":[1]}]"""

    private val albumJson = """
        "album":{"id":"al-1","name":"Blue Train","artist":"John Coltrane","artistId":"ar-1","coverArt":"al-1",
          "songCount":2,"duration":2582,"playCount":7,"created":"2023-11-14T22:13:20Z","starred":"2024-01-01T00:00:00Z",
          "year":1957,"genre":"Jazz","played":"2024-02-02T10:00:00Z","userRating":5,"isCompilation":false,
          "musicBrainzId":"mbid-album","sortName":"blue train","displayArtist":"John Coltrane",
          "artists":[{"id":"ar-1","name":"John Coltrane"}],"genres":[{"name":"Jazz"}],
          "song":[
            {"id":"tr-2","parent":"al-1","isDir":false,"title":"Moment's Notice","album":"Blue Train","artist":"John Coltrane",
             "track":2,"discNumber":1,"year":1957,"genre":"Jazz","coverArt":"al-1","size":61234567,"contentType":"audio/flac",
             "suffix":"flac","duration":548.4,"bitRate":893,"path":"Coltrane/Blue Train/02.flac","albumId":"al-1","artistId":"ar-1",
             "bitDepth":16,"samplingRate":44100,"channelCount":2,"playCount":3,"userRating":4,"created":"2023-11-14T22:13:20Z",
             "replayGain":{"trackGain":-6.5,"albumGain":-7.1,"trackPeak":0.98,"albumPeak":1.0},"mediaType":"song"},
            {"id":"tr-1","parent":"al-1","isDir":false,"title":"Blue Train","album":"Blue Train","artist":"John Coltrane",
             "track":1,"discNumber":1,"duration":643,"suffix":"flac","contentType":"audio/flac","albumId":"al-1","artistId":"ar-1","starred":"2024-01-01T00:00:00Z"},
            {"id":"vid-1","isDir":false,"isVideo":true,"title":"Bonus video","duration":10},
            {"id":"dir-1","isDir":true,"title":"Folder"}
          ]}
    """.trimIndent()

    @Test
    fun `ping reports server info and discovers extensions`() = runTest {
        val fake = FakeServer()
        fake.on("ping", ok())
        fake.on("getOpenSubsonicExtensions", ok(extensions))
        val provider = fake.provider()
        assertFalse(Capability.SYNCED_LYRICS in provider.capabilities)

        val info = provider.ping()
        assertEquals("navidrome", info.productName)
        assertEquals("0.53.0 (13af8ed4)", info.version)
        assertEquals("1.16.1", info.apiVersion)
        assertTrue(info.isOpenSubsonic)
        assertEquals(listOf("songLyrics", "transcodeOffset"), info.extensions)
        assertTrue(Capability.SYNCED_LYRICS in provider.capabilities)
    }

    @Test
    fun `getAlbum maps tracks and skips directories and videos`() = runTest {
        val fake = FakeServer()
        fake.on("getAlbum", ok(albumJson))
        val detail = fake.provider().getAlbum("al-1")

        assertEquals("al-1", fake.lastRequest("getAlbum").queryParameter("id"))
        val album = detail.album
        assertEquals("srv-1", album.serverId)
        assertEquals("Blue Train", album.name)
        assertEquals("ar-1", album.artistId)
        assertEquals(1957, album.year)
        assertEquals(2_582_000L, album.durationMs)
        assertEquals(7L, album.playCount)
        assertEquals(5, album.rating)
        assertTrue(album.starred)
        assertEquals(1_700_000_000_000L, album.createdAt)
        assertNotNull(album.lastPlayedAt)
        assertTrue(album.artworkUrl!!.contains("getCoverArt"))
        assertEquals("600", album.artworkUrl!!.toHttpUrl().queryParameter("size"))

        assertEquals(listOf("tr-1", "tr-2"), detail.tracks.map { it.id })
        val t = detail.tracks[1]
        assertEquals("Moment's Notice", t.title)
        assertEquals(548_400L, t.durationMs)
        assertEquals(893, t.bitrateKbps)
        assertEquals(16, t.bitDepth)
        assertEquals(44_100, t.sampleRateHz)
        assertEquals(2, t.channels)
        assertEquals("flac", t.container)
        assertEquals("audio/flac", t.mimeType)
        assertEquals(61_234_567L, t.sizeBytes)
        assertEquals(-6.5, t.replayGainTrackDb!!, 0.0001)
        assertEquals(0.98, t.replayGainTrackPeak!!, 0.0001)
        assertEquals(4, t.rating)
        assertTrue(t.isLossless)
        assertTrue(detail.tracks[0].starred)
    }

    @Test
    fun `getAlbums maps list types and paging params`() = runTest {
        val fake = FakeServer()
        fake.on("getAlbumList2", ok(""""albumList2":{"album":[{"id":"al-9","name":"X","songCount":1,"duration":10}]}"""))
        val provider = fake.provider()

        val albums = provider.getAlbums(AlbumListType.RECENTLY_ADDED, size = 20, offset = 40)
        assertEquals(1, albums.size)
        var url = fake.lastRequest("getAlbumList2")
        assertEquals("newest", url.queryParameter("type"))
        assertEquals("20", url.queryParameter("size"))
        assertEquals("40", url.queryParameter("offset"))

        provider.getAlbums(AlbumListType.BY_GENRE, genre = "Jazz")
        url = fake.lastRequest("getAlbumList2")
        assertEquals("byGenre", url.queryParameter("type"))
        assertEquals("Jazz", url.queryParameter("genre"))

        provider.getAlbums(AlbumListType.BY_YEAR, fromYear = 1990, toYear = 1999)
        url = fake.lastRequest("getAlbumList2")
        assertEquals("byYear", url.queryParameter("type"))
        assertEquals("1990", url.queryParameter("fromYear"))
        assertEquals("1999", url.queryParameter("toYear"))

        provider.getAlbums(AlbumListType.BY_GENRE, genre = null)
        assertEquals("alphabeticalByName", fake.lastRequest("getAlbumList2").queryParameter("type"))
    }

    @Test
    fun `search3 maps all three sections and getAllTracks pages through songs`() = runTest {
        val fake = FakeServer()
        fake.on("search3") { url ->
            if (url.queryParameter("query") == "") {
                200 to ok(""""searchResult3":{"song":[{"id":"s-${url.queryParameter("songOffset")}","title":"T","duration":1}]}""")
            } else {
                200 to ok(
                    """"searchResult3":{"artist":[{"id":"ar-1","name":"Coltrane","albumCount":3}],
                       "album":[{"id":"al-1","name":"Blue Train","artist":"John Coltrane","songCount":5,"duration":100}],
                       "song":[{"id":"tr-1","title":"Blue Train","duration":643,"suffix":"flac"}]}""",
                )
            }
        }
        val provider = fake.provider()
        val results = provider.search("blue")
        assertEquals(1, results.artists.size)
        assertEquals(3, results.artists[0].albumCount)
        assertEquals("Blue Train", results.albums[0].name)
        assertEquals("tr-1", results.tracks[0].id)
        val url = fake.lastRequest("search3")
        assertEquals("blue", url.queryParameter("query"))
        assertEquals("20", url.queryParameter("artistCount"))
        assertEquals("50", url.queryParameter("songCount"))

        val page = provider.getAllTracks(size = 500, offset = 1000)
        assertEquals("s-1000", page.single().id)
        val all = fake.lastRequest("search3")
        assertEquals("", all.queryParameter("query"))
        assertEquals("0", all.queryParameter("artistCount"))
        assertEquals("500", all.queryParameter("songCount"))
        assertEquals("1000", all.queryParameter("songOffset"))
    }

    @Test
    fun `getAllTracks reports Unsupported when the server ignores empty queries`() = runTest {
        val fake = FakeServer()
        fake.on("search3", ok(""""searchResult3":{}"""))
        fake.on("getRandomSongs", ok(""""randomSongs":{"song":[{"id":"x","title":"X"}]}"""))
        try {
            fake.provider().getAllTracks(500, 0)
            fail("expected Unsupported")
        } catch (e: ProviderException.Unsupported) {
            // expected
        }
    }

    @Test
    fun `synced lyrics via OpenSubsonic extension`() = runTest {
        val fake = FakeServer()
        fake.on("getOpenSubsonicExtensions", ok(extensions))
        fake.on(
            "getLyricsBySongId",
            ok(""""lyricsList":{"structuredLyrics":[{"displayArtist":"A","displayTitle":"T","lang":"eng","offset":100,"synced":true,
                 "line":[{"start":1500,"value":"First"},{"start":4200,"value":"Second"}]}]}"""),
        )
        val track = Track(id = "tr-1", serverId = "srv-1", title = "T", artistName = "A")
        val lyrics = fake.provider().getLyrics(track)
        assertNotNull(lyrics)
        assertTrue(lyrics!!.synced)
        assertEquals(listOf(1400L, 4100L), lyrics.lines.map { it.startMs })
        assertEquals("First\nSecond", lyrics.plainText)
        assertEquals("tr-1", fake.lastRequest("getLyricsBySongId").queryParameter("id"))
    }

    @Test
    fun `plain lyrics fallback without the extension`() = runTest {
        val fake = FakeServer()
        fake.on("getOpenSubsonicExtensions", ok(""""openSubsonicExtensions":[]"""))
        fake.on("getLyrics", ok(""""lyrics":{"artist":"A","title":"T","value":"line one\nline two"}"""))
        val track = Track(id = "tr-1", serverId = "srv-1", title = "T", artistName = "A")
        val lyrics = fake.provider().getLyrics(track)
        assertNotNull(lyrics)
        assertFalse(lyrics!!.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals("A", fake.lastRequest("getLyrics").queryParameter("artist"))

        fake.on("getLyrics", ok(""""lyrics":{}"""))
        assertNull(fake.provider().getLyrics(track))
    }

    @Test
    fun `error envelopes map to provider exceptions`() = runTest {
        val fake = FakeServer()
        fake.on("ping", failed(40, "Wrong username or password"))
        try { fake.provider().ping(); fail() } catch (e: ProviderException.Unauthorized) { assertEquals("Wrong username or password", e.message) }

        fake.on("getAlbum", failed(70, "Album not found"))
        try { fake.provider().getAlbum("nope"); fail() } catch (_: ProviderException.NotFound) { }

        fake.on("getGenres", failed(30, "Incompatible Subsonic REST protocol version. Server must upgrade."))
        try { fake.provider().getGenres(); fail() } catch (_: ProviderException.Unsupported) { }

        fake.on("getPlaylists", failed(0, "boom"))
        try { fake.provider().getPlaylists(); fail() } catch (e: ProviderException.Server) { assertEquals(0, e.code) }

        fake.on("getArtists", "<html>login</html>")
        try { fake.provider().getArtists(); fail() } catch (_: ProviderException.Unknown) { }

        fake.on("getStarred2", "Internal Server Error", status = 500)
        try { fake.provider().getStarred(); fail() } catch (e: ProviderException.Server) { assertEquals(500, e.code) }

        fake.on("getRandomSongs", "", status = 401)
        try { fake.provider().getRandomTracks(); fail() } catch (_: ProviderException.Unauthorized) { }
    }

    @Test
    fun `stars ratings scrobbles and playlist edits send the right params`() = runTest {
        val fake = FakeServer()
        listOf("star", "unstar", "setRating", "scrobble", "updatePlaylist", "deletePlaylist").forEach { fake.on(it, ok()) }
        val provider = fake.provider()

        provider.setStarred("al-1", ItemKind.ALBUM, true)
        assertEquals("al-1", fake.lastRequest("star").queryParameter("albumId"))
        provider.setStarred("ar-1", ItemKind.ARTIST, false)
        assertEquals("ar-1", fake.lastRequest("unstar").queryParameter("artistId"))
        provider.setStarred("tr-1", ItemKind.TRACK, true)
        assertEquals("tr-1", fake.lastRequest("star").queryParameter("id"))

        provider.setRating("tr-1", ItemKind.TRACK, 9)
        assertEquals("5", fake.lastRequest("setRating").queryParameter("rating"))

        provider.reportNowPlaying("tr-1")
        assertEquals("false", fake.lastRequest("scrobble").queryParameter("submission"))
        provider.reportPlayed("tr-1", 1_700_000_000_000L)
        val scrobble = fake.lastRequest("scrobble")
        assertEquals("true", scrobble.queryParameter("submission"))
        assertEquals("1700000000000", scrobble.queryParameter("time"))

        provider.updatePlaylist("p1", name = "Renamed", addTrackIds = listOf("a", "b"), removeIndices = listOf(0, 3))
        val calls = fake.requests.filter { it.pathSegments.last() == "updatePlaylist" }
        assertEquals(2, calls.size)
        assertEquals(listOf("3", "0"), calls[0].queryParameterValues("songIndexToRemove"))
        assertEquals(listOf("a", "b"), calls[1].queryParameterValues("songIdToAdd"))
        assertEquals("Renamed", calls[1].queryParameter("name"))

        provider.deletePlaylist("p1")
        assertEquals("p1", fake.lastRequest("deletePlaylist").queryParameter("id"))
    }

    @Test
    fun `createPlaylist falls back to lookup on servers that return an empty envelope`() = runTest {
        val fake = FakeServer()
        fake.on("createPlaylist", ok())
        fake.on("getPlaylists", ok(""""playlists":{"playlist":[{"id":"p1","name":"Mix","songCount":2,"duration":5,"changed":"2024-01-01T00:00:00Z"}]}"""))
        val playlist = fake.provider().createPlaylist("Mix", listOf("a", "b"))
        assertEquals("p1", playlist.id)
        assertEquals(listOf("a", "b"), fake.lastRequest("createPlaylist").queryParameterValues("songId"))
    }

    @Test
    fun `stream and download urls honour transcode profiles`() = runTest {
        val provider = FakeServer().provider()
        val track = Track(id = "tr-1", serverId = "srv-1", title = "T", mimeType = "audio/flac", container = "flac")

        val raw = provider.streamRequest(track, TranscodeProfile.ORIGINAL)
        var url = raw.url.toHttpUrl()
        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("tr-1", url.queryParameter("id"))
        assertEquals("raw", url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))
        assertEquals("true", url.queryParameter("estimateContentLength"))
        assertNotNull(url.queryParameter("t"))
        assertFalse(raw.isTranscoded)
        assertEquals("audio/flac", raw.mimeType)

        val opus = provider.streamRequest(track, TranscodeProfile.BALANCED)
        // Transcoded streams must not request an estimated length: a short body would cut the track.
        assertNull(opus.url.toHttpUrl().queryParameter("estimateContentLength"))
        url = opus.url.toHttpUrl()
        assertEquals("opus", url.queryParameter("format"))
        assertEquals("128", url.queryParameter("maxBitRate"))
        assertTrue(opus.isTranscoded)
        assertEquals("audio/ogg", opus.mimeType)

        val mp3 = provider.streamRequest(track, TranscodeProfile.CAST_SAFE)
        assertEquals("mp3", mp3.url.toHttpUrl().queryParameter("format"))
        assertEquals("audio/mpeg", mp3.mimeType)

        val flac = provider.streamRequest(track, TranscodeProfile.LOSSLESS_CAPPED)
        url = flac.url.toHttpUrl()
        assertEquals("flac", url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))

        val download = provider.downloadRequest(track).url.toHttpUrl()
        assertEquals("/rest/download", download.encodedPath)
        assertEquals("tr-1", download.queryParameter("id"))

        val art = provider.artworkUrl("al-1", 300)!!.toHttpUrl()
        assertEquals("/rest/getCoverArt", art.encodedPath)
        assertEquals("300", art.queryParameter("size"))
        assertNull(provider.artworkUrl(null))
    }

    @Test
    fun `getArtist merges artist info and sorts albums by year`() = runTest {
        val fake = FakeServer()
        fake.on(
            "getArtist",
            ok(""""artist":{"id":"ar-1","name":"Coltrane","albumCount":2,"coverArt":"ar-1","album":[
                 {"id":"al-a","name":"A","year":1960,"songCount":1,"duration":1},
                 {"id":"al-b","name":"B","year":1965,"songCount":1,"duration":1}]}"""),
        )
        fake.on(
            "getArtistInfo2",
            ok(""""artistInfo2":{"biography":"John Coltrane was a <a href=\"x\">saxophonist</a> &amp; composer.","musicBrainzId":"69158f97-4c07-4c4e-baf8-4e4ab1ed666e",
                 "largeImageUrl":"https://img/large.jpg","similarArtist":[{"id":"ar-2","name":"Miles Davis","albumCount":9}]}"""),
        )
        val detail = fake.provider().getArtist("ar-1")
        assertEquals(listOf("al-b", "al-a"), detail.albums.map { it.id })
        assertEquals("John Coltrane was a saxophonist & composer.", detail.artist.biography)
        assertEquals("69158f97-4c07-4c4e-baf8-4e4ab1ed666e", detail.artist.musicBrainzId)
        assertEquals("Miles Davis", detail.similarArtists.single().name)
        assertTrue(detail.artist.artworkUrl!!.contains("getCoverArt"))
    }

    @Test
    fun `getArtists flattens the index`() = runTest {
        val fake = FakeServer()
        fake.on(
            "getArtists",
            ok(""""artists":{"ignoredArticles":"The El La","index":[
                 {"name":"A","artist":[{"id":"1","name":"Air","albumCount":2,"starred":"2024-01-01T00:00:00Z"}]},
                 {"name":"B","artist":[{"id":"2","name":"Beck","albumCount":5,"artistImageUrl":"https://img/beck.jpg"}]}]}"""),
        )
        val artists = fake.provider().getArtists()
        assertEquals(listOf("Air", "Beck"), artists.map { it.name })
        assertTrue(artists[0].starred)
        assertEquals("https://img/beck.jpg", artists[1].artworkUrl)
    }
}
