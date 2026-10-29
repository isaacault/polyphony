import Foundation
import Testing
@testable import PolyphonyKit

private func ok(_ payload: String) -> HTTPResponse {
    HTTPResponse(status: 200, body: Data(#"{"subsonic-response":{"status":"ok","version":"1.16.1"\#(payload.isEmpty ? "" : "," + payload)}}"#.utf8))
}

struct SubsonicLibraryTests {
    @Test func aPlaylistComesWithItsSongs() async throws {
        let fake = FakeTransport { _ in ok(#""playlist":{"id":"pl-1","name":"Road trip","owner":"isaac","public":true,"songCount":2,"duration":400,"entry":[{"id":"t1","title":"A"},{"id":"t2","title":"B"}]}"#) }
        let p = try await SubsonicClient(credentials: credentials, transport: fake).playlist(id: "pl-1")
        #expect(p.name == "Road trip" && p.isPublic && p.tracks.map(\.id) == ["t1", "t2"])
    }

    @Test func genresAreSortedAndBlankOnesDropped() async throws {
        let fake = FakeTransport { _ in ok(#""genres":{"genre":[{"value":"rock","songCount":3,"albumCount":1},{"value":"","songCount":1},{"value":"Ambient","songCount":9,"albumCount":2}]}"#) }
        let genres = try await SubsonicClient(credentials: credentials, transport: fake).genres()
        #expect(genres.map(\.name) == ["Ambient", "rock"])
    }

    @Test func favouritingUsesTheRightParameter() async throws {
        let fake = FakeTransport { _ in ok("") }
        let client = SubsonicClient(credentials: credentials, transport: fake)
        try await client.setStarred(.album("al-1"), true)
        try await client.setStarred(.track("t1"), false)
        #expect(fake.calls[0].url.path == "/rest/star" && query(fake.calls[0].url)["albumId"] == "al-1")
        #expect(fake.calls[1].url.path == "/rest/unstar" && query(fake.calls[1].url)["id"] == "t1")
    }

    @Test func playlistEditsRepeatTheirParameters() async throws {
        let fake = FakeTransport { _ in ok("") }
        try await SubsonicClient(credentials: credentials, transport: fake)
            .updatePlaylist(id: "pl-1", adding: ["t3", "t4"], removingIndices: [0, 2])
        let items = URLComponents(url: try #require(fake.calls.first?.url), resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.filter { $0.name == "songIdToAdd" }.map(\.value) == ["t3", "t4"])
        #expect(items.filter { $0.name == "songIndexToRemove" }.map(\.value) == ["0", "2"])
    }

    @Test func scrobblesCarryTheTimeAndKind() async throws {
        let fake = FakeTransport { _ in ok("") }
        try await SubsonicClient(credentials: credentials, transport: fake)
            .scrobble(trackID: "t1", at: Date(timeIntervalSince1970: 1_700_000_000), submission: true)
        let q = query(try #require(fake.calls.first?.url))
        #expect(q["id"] == "t1" && q["time"] == "1700000000000" && q["submission"] == "true")
    }

    @Test func syncedLyricsArePreferred() async throws {
        let fake = FakeTransport { _ in ok(#""lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"plain"}]},{"synced":true,"line":[{"start":1000,"value":"timed"}]}]}"#) }
        let l = try #require(try await SubsonicClient(credentials: credentials, transport: fake).lyrics(trackID: "t1"))
        #expect(l.synced && l.lines == [LyricLine(startMs: 1000, text: "timed")])
    }

    @Test func noLyricsCallMeansNoLyrics() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: Data(#"{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}"#.utf8)) }
        #expect(try await SubsonicClient(credentials: credentials, transport: fake).lyrics(trackID: "t1") == nil)
    }
}
