import Foundation
import Testing
@testable import PolyphonyKit

private let base = URL(string: "https://continuo.ault.me")!

private actor Store: SessionStore {
    var saved: ContinuoSession?
    init(_ s: ContinuoSession?) { saved = s }
    func load() async -> ContinuoSession? { saved }
    func save(_ session: ContinuoSession?) async { saved = session }
}

private func session(_ access: String = "acc-1", refresh: String = "ref-1") -> Data {
    Data(#"{"accessToken":"\#(access)","expiresIn":3600,"refreshToken":"\#(refresh)","user":{"id":1,"username":"isaac","role":"admin"},"device":{"id":"dev-1","name":"Mac"},"library":{"type":"subsonic","baseUrl":"https://continuo.ault.me","username":"isaac","password":"s"}}"#.utf8)
}

private func service(_ fake: FakeTransport) async throws -> ContinuoService {
    let s = try JSONDecoder().decode(ContinuoSession.self, from: session())
    let account = ContinuoAccount(client: ContinuoClient(baseURL: base, transport: fake), store: Store(s))
    _ = await account.restore()
    return ContinuoService(account: account)
}

private func json(_ status: Int, _ body: String) -> HTTPResponse { HTTPResponse(status: status, body: Data(body.utf8)) }

struct ContinuoServiceTests {
    @Test func searchKeepsContinuosRankingAndLead() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("search")) }
        let r = try await service(fake).search("alaskan tapes", quick: true)
        let request = try #require(fake.calls.first)
        #expect(request.headers["Authorization"] == "Bearer acc-1")
        #expect(query(request.url)["quick"] == "true" && query(request.url)["types"] == "album,artist,track")
        #expect(r.ranked && r.artists.count == 1 && r.albums.count == 2 && r.tracks.count == 2)
        guard case let .album(best)? = r.best else { Issue.record("best should be the album"); return }
        #expect(best.title == "We All Speak in Poems" && best.availability == .inLibrary(albumID: "al-1"))
    }

    @Test func availabilityAndRecordingIDsMap() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("search")) }
        let r = try await service(fake).search("alaskan")
        #expect(r.albums[1].availability == .requested(requestID: 7, state: "downloading", stillLooking: false))
        #expect(r.tracks[0].availability == .inLibrary(albumID: "al-1") && r.tracks[0].recordingID == "speaker")
        #expect(r.tracks[1].availability == .requestable && r.tracks[1].albumReleaseGroupID == "scream")
    }

    @Test func onlyContinuosOwnArtworkIsKept() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("search")) }
        let r = try await service(fake).search("alaskan")
        #expect(r.artists[0].artworkURL?.host == "continuo.ault.me")
        #expect(r.albums[0].artworkURL == nil, "coverartarchive.org is a third-party host")
    }

    @Test func requestOutcomesFromConflicts() async throws {
        let answers = [
            json(201, #"{"id":42}"#),
            json(409, #"{"error":{"code":"already_requested","message":"x","requestId":7}}"#),
            json(409, #"{"error":{"code":"in_library","message":"x","navidromeAlbumId":"al-1"}}"#),
            json(409, #"{"error":{"code":"added_to_library","message":"x"}}"#),
        ]
        var i = 0
        let fake = FakeTransport { _ in defer { i += 1 }; return answers[i] }
        let s = try await service(fake)
        #expect(try await s.requestAlbum(releaseGroupID: "rg") == .requested(id: 42))
        #expect(try await s.requestAlbum(releaseGroupID: "rg") == .alreadyRequested(id: 7))
        #expect(try await s.requestAlbum(releaseGroupID: "rg") == .inLibrary(albumID: "al-1"))
        #expect(try await s.requestTrack(recordingID: "rec", releaseGroupID: "rg") == .addedBack)
        let body = try JSONSerialization.jsonObject(with: try #require(fake.calls[3].body)) as? [String: String]
        #expect(body?["kind"] == "track" && body?["musicBrainzRecordingId"] == "rec")
    }

    @Test func noLyricsIsNilNotAnError() async throws {
        let fake = FakeTransport { _ in json(404, #"{"error":{"code":"not_found","message":"No lyrics"}}"#) }
        #expect(try await service(fake).lyrics(artist: "a", title: "b", album: nil, durationMs: nil) == nil)
    }

    @Test func syncedLyricsKeepTheirTimes() async throws {
        let fake = FakeTransport { _ in json(200, #"{"synced":true,"lines":[{"startMs":1200,"text":"Hello"},{"startMs":3400,"text":"again"}],"source":"lrclib"}"#) }
        let l = try #require(try await service(fake).lyrics(artist: "a", title: "b", album: "c", durationMs: 1000))
        #expect(l.synced && l.lines.map(\.startMs) == [1200, 3400] && l.source == "LRCLIB")
    }

    @Test func aRefusedTokenIsRefreshedAndTriedOnceMore() async throws {
        let fake = FakeTransport { request in
            if request.url.path == "/v1/auth/refresh" { return HTTPResponse(status: 200, body: session("acc-2", refresh: "ref-2")) }
            return request.headers["Authorization"] == "Bearer acc-2"
                ? json(200, #"{"items":[{"id":1,"title":"Poems","status":"available","play":{"navidromeAlbumId":"al-1"}}]}"#)
                : json(401, #"{"error":{"code":"invalid_token","message":"Invalid or expired token."}}"#)
        }
        let requests = try await service(fake).requests()
        #expect(requests.first?.isDone == true && requests.first?.albumID == "al-1")
        #expect(fake.calls.map(\.url.path) == ["/v1/requests", "/v1/auth/refresh", "/v1/requests"])
    }

    @Test func playNowNeedsARecording() async throws {
        let fake = FakeTransport { _ in json(201, #"{"id":"s1","status":"searching"}"#) }
        let s = try await service(fake)
        var track = CatalogTrack(key: "x", title: "Loaded", artist: "Primal Scream", durationMs: 422373, albumTitle: nil,
                                 albumReleaseGroupID: "scream", availability: .requestable, recordingID: nil, artworkURL: nil)
        await #expect(throws: ContinuoError.self) { _ = try await s.startPlayNow(track) }
        track.recordingID = "loaded"
        let stream = try await s.startPlayNow(track)
        #expect(stream.id == "s1" && !stream.playable)
        #expect(s.playNowAudioURL(id: "s1").absoluteString == "https://continuo.ault.me/v1/play-now/s1/audio")
    }
}
