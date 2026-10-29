import Foundation
import Testing
@testable import PolyphonyKit

private func json(_ status: Int, _ body: String) -> HTTPResponse { HTTPResponse(status: status, body: Data(body.utf8)) }

private actor MemoryServers: ServerStore {
    var saved: SavedServer?
    func load() async -> SavedServer? { saved }
    func save(_ server: SavedServer?) async { saved = server }
}

struct ConnectionTests {
    @Test func aContinuoSignsInAsContinuo() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("login")) }
        let server = try await Connector.connect(address: "continuo.ault.me", username: "isaac", password: "pw",
                                                 deviceName: "Mac", transport: fake)
        #expect(server.kind == .continuo && server.subsonicPassword == "device-secret")
        #expect(server.subsonic.baseURL.absoluteString == "https://continuo.ault.me")
    }

    @Test func aWrongPasswordAtContinuoIsAWrongPassword() async {
        let fake = FakeTransport { _ in json(401, #"{"error":{"code":"invalid_credentials","message":"Invalid username or password."}}"#) }
        await #expect(throws: ConnectError.wrongPassword) {
            _ = try await Connector.connect(address: "continuo.ault.me", username: "a", password: "b", deviceName: "Mac", transport: fake)
        }
        #expect(fake.calls.count == 1, "no fall back to Subsonic")
    }

    @Test func aPlainNavidromeSignsInAsSubsonic() async throws {
        let fake = FakeTransport { request in
            request.url.path == "/v1/auth/login"
                ? HTTPResponse(status: 404, body: Data("404 page not found".utf8))
                : json(200, #"{"subsonic-response":{"status":"ok","version":"1.16.1"}}"#)
        }
        let server = try await Connector.connect(address: "http://192.168.0.10:4533", username: "isaac", password: "pw",
                                                 deviceName: "Mac", transport: fake)
        #expect(server.kind == .subsonic && server.session == nil && server.subsonicPassword == "pw")
        #expect(fake.calls.last?.url.path == "/rest/ping")
    }

    @Test func aSubsonicRefusalIsAWrongPassword() async {
        let fake = FakeTransport { request in
            request.url.path == "/v1/auth/login" ? json(404, "") : HTTPResponse(status: 200, body: fixture("failed"))
        }
        await #expect(throws: ConnectError.wrongPassword) {
            _ = try await Connector.connect(address: "music.example.com", username: "a", password: "b", deviceName: "Mac", transport: fake)
        }
    }

    @Test func somethingElseEntirelyIsNotAMusicServer() async {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: Data("<html>hello</html>".utf8)) }
        await #expect(throws: ConnectError.notAMusicServer) {
            _ = try await Connector.connect(address: "example.com", username: "a", password: "b", deviceName: "Mac", transport: fake)
        }
    }

    @Test func refreshedSessionsGoBackIntoTheSavedServer() async throws {
        let servers = MemoryServers()
        let session = try JSONDecoder().decode(ContinuoSession.self, from: fixture("login"))
        await servers.save(SavedServer(kind: .continuo, address: URL(string: "https://continuo.ault.me")!, subsonicUsername: "isaac",
                                       subsonicPassword: "old", salt: "s", session: session))
        let store = ServerSessionStore(servers)
        await store.save(session)
        #expect(await servers.saved?.subsonicPassword == "device-secret")
        await store.save(nil)
        #expect(await servers.saved == nil, "an ended session signs the app out")
    }
}
