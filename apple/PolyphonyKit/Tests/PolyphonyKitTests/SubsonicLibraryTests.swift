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
        #expect(!items.contains { $0.name == "public" }, "left as it is unless asked")
    }

    @Test func aPlaylistGoesPublicAndAlbumsCarryTheirRating() async throws {
        let fake = FakeTransport { _ in ok("") }
        try await SubsonicClient(credentials: credentials, transport: fake).updatePlaylist(id: "pl-1", isPublic: true)
        #expect(query(try #require(fake.calls.first?.url))["public"] == "true")
        let album = try JSONDecoder().decode(AlbumDTO.self, from: Data(#"{"id":"al","name":"A","userRating":4}"#.utf8)).model
        #expect(album.rating == 4)
        #expect(try JSONDecoder().decode(AlbumDTO.self, from: Data(#"{"id":"al","name":"A","userRating":0}"#.utf8)).model.rating == nil)
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

struct ReleaseTypeTests {
    @Test func aSingleSaysSoUnderItsTitle() {
        let single = Album(id: "a", name: "By Any Means", artistName: "Jorja Smith", releaseType: .single)
        #expect(single.subtitle == "Single · Jorja Smith" && single.isShortRelease)
        #expect(Album(id: "b", name: "Lost & Found", artistName: "Jorja Smith", releaseType: .album).subtitle == "Jorja Smith")
        #expect(Album(id: "c", name: "Rip", artistName: "X").subtitle == "X", "untagged is an album")
        #expect(Album(id: "d", name: "E", artistName: "X", releaseType: .ep).subtitle == "EP · X")
    }

    @Test func anArtistsAlbumsGroupAlbumsThenEPsThenSingles() {
        let albums = [
            Album(id: "s", name: "By Any Means", year: 2023, releaseType: .single),
            Album(id: "a1", name: "Old", year: 2018, releaseType: .album),
            Album(id: "a2", name: "New", year: 2023),
            Album(id: "e", name: "EP", year: 2016, releaseType: .ep),
        ]
        let groups = Album.grouped(albums)
        #expect(groups.map(\.type) == [.album, .ep, .single])
        #expect(groups[0].albums.map(\.id) == ["a2", "a1"], "newest first, untagged with the albums")
    }

    @Test func navidromesReleaseTypesComeThrough() throws {
        let json = #"{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al1","name":"By Any Means","artist":"Jorja Smith","songCount":1,"releaseTypes":["Single"],"song":[]}}}"#
        let client = SubsonicClient(credentials: credentials, transport: FakeTransport { _ in HTTPResponse(status: 200, body: Data(json.utf8)) })
        let album = try awaitSync { try await client.album(id: "al1") }
        #expect(album.releaseType == .single)
    }
}

/// Runs an async throwing call to completion from a synchronous test.
private func awaitSync<T: Sendable>(_ call: @escaping @Sendable () async throws -> T) throws -> T {
    let box = ResultBox<T>()
    let done = DispatchSemaphore(value: 0)
    Task {
        do { box.result = .success(try await call()) } catch { box.result = .failure(error) }
        done.signal()
    }
    done.wait()
    return try box.result!.get()
}

private final class ResultBox<T>: @unchecked Sendable { var result: Result<T, Error>? }
