import Foundation
import Testing
@testable import PolyphonyKit

struct SubsonicTests {
    @Test func requestsAreSignedWithATokenAndTheStableSalt() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("getArtists")) }
        _ = try await SubsonicClient(credentials: credentials, transport: fake).artists()
        let url = try #require(fake.calls.first?.url)
        #expect(url.path == "/rest/getArtists")
        let q = query(url)
        #expect(q["u"] == "isaac" && q["s"] == "0123456789abcdef")
        #expect(q["t"] == MD5.hex("device-secret0123456789abcdef"))
        #expect(q["p"] == nil && q["v"] == "1.16.1" && q["c"] == "Polyphony" && q["f"] == "json")
    }

    @Test func legacyAuthSendsTheHexEncodedPassword() {
        var legacy = credentials
        legacy.legacyAuth = true
        let q = query(SubsonicClient(credentials: legacy).coverArtURL(id: "x"))
        #expect(q["p"] == "enc:6465766963652d736563726574" && q["t"] == nil)
    }

    @Test func coverAndStreamURLsAreStableAcrossCalls() {
        let client = SubsonicClient(credentials: credentials)
        #expect(client.coverArtURL(id: "al-1", size: 300) == client.coverArtURL(id: "al-1", size: 300))
        let q = query(client.streamURL(trackID: "tr-1", maxBitRateKbps: 192, format: "mp3"))
        #expect(q["id"] == "tr-1" && q["maxBitRate"] == "192" && q["format"] == "mp3")
    }

    @Test func downloadsAskForTheOriginalFile() {
        let url = SubsonicClient(credentials: credentials).downloadURL(trackID: "tr-1")
        #expect(url.path == "/rest/download" && query(url)["id"] == "tr-1")
    }

    @Test func aPlusInASearchStaysAPlus() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: Data(#"{"subsonic-response":{"status":"ok","searchResult3":{}}}"#.utf8)) }
        _ = try await SubsonicClient(credentials: credentials, transport: fake).search("C+C Music Factory")
        let url = try #require(fake.calls.first?.url)
        #expect(url.absoluteString.contains("query=C%2BC%20Music%20Factory"))
        #expect(query(url)["query"] == "C+C Music Factory")
    }

    @Test func artistsComeFromEveryIndex() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("getArtists")) }
        let artists = try await SubsonicClient(credentials: credentials, transport: fake).artists()
        #expect(artists.map(\.name) == ["Alaskan Tapes", "Fat Freddy's Drop"])
        #expect(artists[1].starred && !artists[0].starred && artists[1].albumCount == 2)
    }

    @Test func anAlbumComesWithItsTracks() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("getAlbum")) }
        let album = try await SubsonicClient(credentials: credentials, transport: fake).album(id: "al-1")
        #expect(album.name == "We All Speak in Poems" && album.year == 2016 && album.tracks.count == 2)
        let speaker = album.tracks[0]
        #expect(speaker.title == "Speaker" && speaker.trackNumber == 1 && speaker.bitDepth == 24)
        #expect(speaker.isLossless && speaker.container == "flac" && speaker.sizeBytes == 24_117_435)
        #expect(album.tracks[1].starred && album.tracks[1].rating == 4 && speaker.rating == nil)
    }

    @Test func aFailedAnswerIsASubsonicError() async {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("failed")) }
        await #expect(throws: SubsonicError.failed(code: 40, message: "Wrong username or password")) {
            try await SubsonicClient(credentials: credentials, transport: fake).ping()
        }
    }

    @Test func continuosFacadeRefusalIsReadFromItsBodyNotJustTheStatus() async throws {
        // continuo's façade answers a bad credential with HTTP 401 and a Subsonic error body.
        let fake = FakeTransport { _ in HTTPResponse(status: 401, body: fixture("failed")) }
        do {
            try await SubsonicClient(credentials: credentials, transport: fake).ping()
            Issue.record("expected a failure")
        } catch let error as SubsonicError {
            #expect(error.isAuthFailure)
        }
    }

    @Test func aProxyErrorPageIsAnHTTPError() async {
        let fake = FakeTransport { _ in HTTPResponse(status: 502, body: Data("<html>Bad gateway</html>".utf8)) }
        await #expect(throws: SubsonicError.http(status: 502)) {
            try await SubsonicClient(credentials: credentials, transport: fake).ping()
        }
    }

    @Test("addresses people type", arguments: [
        ("music.example.com", "https://music.example.com"),
        ("https://music.example.com/", "https://music.example.com"),
        ("http://192.168.0.10:4533/rest", "http://192.168.0.10:4533"),
        (" https://example.com/navidrome/ ", "https://example.com/navidrome"),
    ])
    func serverAddresses(input: String, expected: String) throws {
        #expect(try ServerAddress.normalise(input).absoluteString == expected)
    }

    @Test func garbageIsNotAnAddress() {
        #expect(throws: ServerAddress.Invalid.self) { try ServerAddress.normalise("https://") }
    }
}
