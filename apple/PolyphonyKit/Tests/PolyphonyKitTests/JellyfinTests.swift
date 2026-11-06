import Foundation
import Testing
@testable import PolyphonyKit

private let base = URL(string: "https://jf.example.com")!
private let session = JellyfinSession(userID: "u1", accessToken: "tok", deviceID: "dev")

private func json(_ text: String, status: Int = 200) -> HTTPResponse { HTTPResponse(status: status, body: Data(text.utf8)) }

private func client(_ fake: FakeTransport) -> JellyfinClient { JellyfinClient(baseURL: base, session: session, transport: fake) }

private let trackJSON = #"""
{"Id":"t1","Name":"Speaker","Album":"Views","AlbumId":"al1","AlbumPrimaryImageTag":"x","Artists":["Alaskan Tapes","Guest"],
 "ArtistItems":[{"Name":"Alaskan Tapes","Id":"ar1"}],"AlbumArtist":"Alaskan Tapes","IndexNumber":3,"ParentIndexNumber":1,"ProductionYear":2019,
 "Genres":["Ambient"],"RunTimeTicks":1800000000,"UserData":{"IsFavorite":true,"Likes":true,"PlayCount":7},
 "MediaSources":[{"Container":"flac,ogg","Bitrate":900000,"Size":12345,"MediaStreams":[{"Type":"Video"},{"Type":"Audio","SampleRate":96000,"BitDepth":24,"Channels":2}]}],
 "NormalizationGain":-3.5,"AlbumNormalizationGain":-4.0,"Path":"/music/a.flac"}
"""#

struct JellyfinTests {
    @Test func aServerSaysItsJellyfin() async throws {
        let yes = FakeTransport { _ in json(#"{"ProductName":"Jellyfin Server","ServerName":"Home","Id":"x"}"#) }
        #expect(try await JellyfinClient.isJellyfin(at: base, transport: yes))
        #expect(yes.calls.first?.url.path == "/System/Info/Public")
        let navidrome = FakeTransport { _ in json("<html>not found</html>", status: 404) }
        #expect(try await !JellyfinClient.isJellyfin(at: base, transport: navidrome))
    }

    @Test func signingInGivesATokenForThisDevice() async throws {
        let fake = FakeTransport { _ in json(#"{"AccessToken":"tok","User":{"Id":"u1","Name":"Isaac"}}"#) }
        let (s, name) = try await JellyfinClient.signIn(baseURL: base, username: "isaac", password: "pw", deviceName: "My Mac", transport: fake)
        #expect(s.userID == "u1" && s.accessToken == "tok" && !s.deviceID.isEmpty && name == "Isaac")
        let call = try #require(fake.calls.first)
        #expect(call.method == "POST" && call.url.path == "/Users/AuthenticateByName")
        #expect(call.headers["Authorization"]?.hasPrefix("MediaBrowser Client=\"Polyphony\"") == true)
        #expect(String(decoding: call.body ?? Data(), as: UTF8.self).contains("\"Pw\":\"pw\""))
    }

    @Test func aWrongPasswordIsAWrongPassword() async {
        let fake = FakeTransport { _ in json("", status: 401) }
        await #expect(throws: ConnectError.wrongPassword) {
            _ = try await JellyfinClient.signIn(baseURL: base, username: "i", password: "x", deviceName: "M", transport: fake)
        }
    }

    @Test func theConnectorFindsJellyfinAfterContinuoSaysNo() async throws {
        let fake = FakeTransport { request in
            switch request.url.path {
            case "/System/Info/Public": json(#"{"ProductName":"Jellyfin Server"}"#)
            case "/Users/AuthenticateByName": json(#"{"AccessToken":"tok","User":{"Id":"u1","Name":"Isaac"}}"#)
            default: json("", status: 404)
            }
        }
        let saved = try await Connector.connect(address: "jf.example.com", username: "isaac", password: "pw", deviceName: "M", transport: fake)
        #expect(saved.kind == .jellyfin && saved.jellyfin?.accessToken == "tok" && saved.subsonicUsername == "Isaac")
        #expect(saved.subsonicPassword.isEmpty, "the password isn't kept: the token is")
        #expect(saved.libraryBaseURL.absoluteString == "https://jf.example.com")
        #expect(saved.library() is JellyfinClient)
    }

    @Test func aSongCarriesWhatTheAppShows() async throws {
        let fake = FakeTransport { _ in json("{\"Items\":[\(trackJSON)]}") }
        let t = try await client(fake).songs(.random, size: 1)[0]
        #expect(t.title == "Speaker" && t.albumID == "al1" && t.albumName == "Views" && t.artistName == "Alaskan Tapes, Guest" && t.artistID == "ar1")
        #expect(t.trackNumber == 3 && t.discNumber == 1 && t.duration == 180 && t.starred && t.rating == 5)
        #expect(t.container == "flac" && t.bitRateKbps == 900 && t.sampleRateHz == 96000 && t.bitDepth == 24 && t.sizeBytes == 12345)
        #expect(t.coverArtID == "al1", "the album's cover")
        #expect(t.replayGain?.trackGainDb == -3.5 && t.replayGain?.albumGainDb == -4.0)
        #expect(t.details?.year == 2019 && t.details?.genre == "Ambient" && t.details?.playCount == 7 && t.details?.path == "/music/a.flac" && t.details?.channels == 2)
        #expect(t.isLossless)
    }

    @Test func albumListsAskAsTheAndroidAppAsks() async throws {
        let fake = FakeTransport { _ in json(#"{"Items":[{"Id":"al1","Name":"Views","AlbumArtist":"AT","AlbumArtists":[{"Name":"AT","Id":"ar1"}],"ProductionYear":2019,"ChildCount":8,"CumulativeRunTimeTicks":18000000000,"ImageTags":{"Primary":"x"},"UserData":{"IsFavorite":true}}]}"#) }
        let albums = try await client(fake).albums(.frequent, size: 30)
        #expect(albums[0].name == "Views" && albums[0].artistName == "AT" && albums[0].artistID == "ar1" && albums[0].songCount == 8)
        #expect(albums[0].duration == 1800 && albums[0].coverArtID == "al1" && albums[0].starred)
        let q = query(try #require(fake.calls.first?.url))
        #expect(q["includeItemTypes"] == "MusicAlbum" && q["sortBy"] == "PlayCount,SortName" && q["filters"] == "IsPlayed")
        #expect(q["limit"] == "30" && q["startIndex"] == "0" && q["userId"] == "u1" && q["recursive"] == "true")
    }

    @Test func anAlbumHasItsSongsInOrder() async throws {
        let fake = FakeTransport { request in
            request.url.path == "/Items/al1" ? json(#"{"Id":"al1","Name":"Views"}"#) : json("{\"Items\":[\(trackJSON)]}")
        }
        let album = try await client(fake).album(id: "al1")
        #expect(album.tracks.map(\.id) == ["t1"] && album.songCount == 1)
        let list = try #require(fake.calls.first { $0.url.path == "/Items" })
        #expect(query(list.url)["parentId"] == "al1" && query(list.url)["sortBy"] == "ParentIndexNumber,IndexNumber,SortName")
    }

    @Test func artistsFallBackToItemsOnAServerWithoutTheList() async throws {
        let fake = FakeTransport { request in
            request.url.path == "/Artists/AlbumArtists" ? json("", status: 404) : json(#"{"Items":[{"Id":"ar1","Name":"AT","AlbumCount":2}]}"#)
        }
        let artists = try await client(fake).artists()
        #expect(artists.map(\.name) == ["AT"] && artists[0].albumCount == 2)
    }

    @Test func theEmptySearchListsSongsAPageAtATime() async throws {
        let fake = FakeTransport { _ in json("{\"Items\":[\(trackJSON)]}") }
        let r = try await client(fake).search("", artists: 0, albums: 0, tracks: 500, trackOffset: 1000)
        #expect(r.tracks.count == 1 && r.albums.isEmpty && fake.calls.count == 1)
        #expect(query(try #require(fake.calls.first?.url))["startIndex"] == "1000")
    }

    @Test func favouritesAndLikes() async throws {
        let fake = FakeTransport { _ in json("") }
        let c = client(fake)
        try await c.setStarred(.album("al1"), true)
        try await c.setStarred(.track("t1"), false)
        try await c.setRating(id: "t1", 4)
        try await c.setRating(id: "t1", 2)
        try await c.setRating(id: "t1", 0)
        let calls = fake.calls
        #expect(calls[0].method == "POST" && calls[0].url.path == "/UserFavoriteItems/al1")
        #expect(calls[1].method == "DELETE" && calls[1].url.path == "/UserFavoriteItems/t1")
        #expect(query(calls[2].url)["likes"] == "true" && query(calls[3].url)["likes"] == "false")
        #expect(calls[4].method == "DELETE" && calls[4].url.path == "/UserItems/t1/Rating")
    }

    @Test func nowPlayingAndACountedPlay() async throws {
        let fake = FakeTransport { _ in json("") }
        try await client(fake).scrobble(trackID: "t1", submission: false)
        try await client(fake).scrobble(trackID: "t1", at: Date(timeIntervalSince1970: 0), submission: true)
        #expect(fake.calls[0].url.path == "/Sessions/Playing" && String(decoding: fake.calls[0].body ?? Data(), as: UTF8.self).contains("t1"))
        #expect(fake.calls[1].url.path == "/UserPlayedItems/t1" && query(fake.calls[1].url)["datePlayed"] == "1970-01-01T00:00:00Z")
    }

    @Test func aPlaylistComesWithItsSongsAndWhetherItsPublic() async throws {
        let fake = FakeTransport { request in
            switch request.url.path {
            case "/Items/p1": json(#"{"Id":"p1","Name":"Mix","ChildCount":99}"#)
            case "/Playlists/p1": json(#"{"OpenAccess":true}"#)
            default: json("{\"Items\":[\(trackJSON)]}")
            }
        }
        let p = try await client(fake).playlist(id: "p1")
        #expect(p.name == "Mix" && p.isPublic && p.tracks.count == 1 && p.songCount == 1)
    }

    @Test func removingFromAPlaylistGoesByEntryNotPlace() async throws {
        let fake = FakeTransport { request in
            request.method == "GET" ? json(#"{"Items":[{"Id":"t1","PlaylistItemId":"e1"},{"Id":"t2","PlaylistItemId":"e2"}]}"#) : json("")
        }
        try await client(fake).updatePlaylist(id: "p1", name: "New", adding: ["t9"], removingIndices: [1])
        let writes = fake.calls.filter { $0.method != "GET" }
        #expect(writes[0].url.path == "/Playlists/p1" && String(decoding: writes[0].body ?? Data(), as: UTF8.self).contains("New"))
        #expect(writes[1].method == "DELETE" && query(writes[1].url)["entryIds"] == "e2")
        #expect(writes[2].method == "POST" && query(writes[2].url)["ids"] == "t9")
    }

    @Test func lyricsWithTimingsOrNone() async throws {
        let fake = FakeTransport { _ in json(#"{"Metadata":{"IsSynced":true},"Lyrics":[{"Text":"First","Start":12000000}]}"#) }
        let l = try #require(try await client(fake).lyrics(trackID: "t1"))
        #expect(l.synced && l.lines[0].startMs == 1200 && l.lines[0].text == "First")
        #expect(try await client(FakeTransport { _ in json("", status: 404) }).lyrics(trackID: "t1") == nil)
    }

    @Test func theOriginalDirectPlaysAndALossyProfileNeverDirectPlaysLossless() throws {
        let c = JellyfinClient(baseURL: base, session: session, transport: FakeTransport { _ in json("") })
        let original = query(c.streamURL(trackID: "t1", choice: StreamChoice()))
        #expect(original["container"]?.contains("flac") == true && original["api_key"] == "tok" && original["ApiKey"] == "tok")
        #expect(original["maxStreamingBitrate"] == nil && original["audioBitRate"] == nil)
        let mp3 = query(c.streamURL(trackID: "t1", choice: StreamChoice(format: "mp3", maxBitRateKbps: 160)))
        #expect(mp3["container"]?.contains("flac") == false, "a lossy profile")
        #expect(mp3["audioCodec"] == "mp3" && mp3["transcodingContainer"] == "mp3" && mp3["maxStreamingBitrate"] == "160000" && mp3["audioBitRate"] == "160000")
        let capped = query(c.streamURL(trackID: "t1", choice: .forMac(.losslessCapped, container: "flac")))
        #expect(capped["audioCodec"] == "flac" && capped["maxAudioSampleRate"] == "48000" && capped["maxAudioBitDepth"] == "16" && capped["container"]?.contains("flac") == true)
    }

    @Test func aFileAndACoverLoadWithoutHeaders() {
        let c = JellyfinClient(baseURL: base, session: session, transport: FakeTransport { _ in json("") })
        #expect(c.downloadURL(trackID: "t1").path == "/Items/t1/Download" && query(c.downloadURL(trackID: "t1"))["api_key"] == "tok")
        let cover = c.coverArtURL(id: "al1", size: 300)
        #expect(cover.path == "/Items/al1/Images/Primary" && query(cover)["maxWidth"] == "300")
    }

    @Test func aJellyfinServerIsKeptAndBackedUpWithItsToken() throws {
        let saved = SavedServer(kind: .jellyfin, address: base, subsonicUsername: "Isaac", subsonicPassword: "", salt: "", session: nil, jellyfin: session)
        let back = try JSONDecoder().decode(SavedServer.self, from: JSONEncoder().encode(saved))
        #expect(back == saved)
        #expect(AppBackup.withoutDeviceSession(saved).jellyfin == session && !AppBackup.needsSignIn(saved))
        let old = #"{"kind":"subsonic","address":"https://n","subsonicUsername":"i","subsonicPassword":"p","salt":"s"}"#
        #expect(try JSONDecoder().decode(SavedServer.self, from: Data(old.utf8)).jellyfin == nil, "a server saved before Jellyfin")
    }
}

struct SubsonicChoiceTests {
    @Test func theSameChoiceAsksSubsonicForTheFileOrATranscode() {
        let c = SubsonicClient(credentials: credentials, transport: FakeTransport { _ in HTTPResponse(status: 200) })
        #expect(query(c.streamURL(trackID: "t", choice: StreamChoice()))["format"] == "raw")
        let mp3 = query(c.streamURL(trackID: "t", choice: StreamChoice(format: "mp3", maxBitRateKbps: 160)))
        #expect(mp3["format"] == "mp3" && mp3["maxBitRate"] == "160")
        let capped = query(c.streamURL(trackID: "t", choice: StreamChoice(maxBitRateKbps: 128)))
        #expect(capped["format"] == nil && capped["maxBitRate"] == "128", "the server's own default transcoding")
    }
}
