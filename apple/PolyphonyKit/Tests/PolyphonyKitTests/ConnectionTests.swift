import Foundation
import Testing
@testable import PolyphonyKit

private func json(_ status: Int, _ body: String) -> HTTPResponse { HTTPResponse(status: status, body: Data(body.utf8)) }

private actor MemoryServers: ServerStore {
    var list = ServerList()
    func load() async -> ServerList { list }
    func save(_ list: ServerList) async { self.list = list }
}

private func saved(_ host: String, _ user: String = "isaac", kind: SavedServer.Kind = .subsonic) -> SavedServer {
    SavedServer(kind: kind, address: URL(string: "https://\(host)")!, subsonicUsername: user, subsonicPassword: "pw", salt: "s", session: nil)
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

    @Test func refreshedSessionsGoBackIntoTheirOwnServer() async throws {
        let servers = MemoryServers()
        let session = try JSONDecoder().decode(ContinuoSession.self, from: fixture("login"))
        let continuo = SavedServer(kind: .continuo, address: URL(string: "https://continuo.ault.me")!, subsonicUsername: "isaac",
                                   subsonicPassword: "old", salt: "s", session: session)
        var list = ServerList()
        list.add(continuo)
        list.add(saved("navidrome.lan"))   // now showing the other one
        await servers.save(list)
        let store = ServerSessionStore(servers, key: continuo.key)
        await store.save(session)
        #expect(await servers.list.server(continuo.key)?.subsonicPassword == "device-secret")
        #expect(await servers.list.active?.address.host == "navidrome.lan", "a refresh doesn't switch servers")
        await store.save(nil)
        #expect(await servers.list.servers.map { $0.address.host } == ["navidrome.lan"], "an ended session signs that server out")
    }

    @Test func theServerList() {
        var list = ServerList()
        list.add(saved("a"))
        list.add(saved("b"))
        #expect(list.servers.count == 2 && list.active?.address.host == "b", "signing in shows the new one")
        list.add(SavedServer(kind: .subsonic, address: URL(string: "https://a")!, subsonicUsername: "isaac", subsonicPassword: "new", salt: "t", session: nil))
        #expect(list.servers.count == 2 && list.servers[0].subsonicPassword == "new" && list.active?.address.host == "a", "again: replaced in place")
        #expect(list.activate(saved("b").key) && !list.activate("nope") && list.active?.address.host == "b")
        list.remove(saved("b").key)
        #expect(list.active?.address.host == "a", "the next one takes over")
        list.remove(saved("a").key)
        #expect(list.active == nil && list.servers.isEmpty)
        #expect(saved("a").key != saved("a", "caleb").key)
    }

    @Test func theKeychainsOneServerFromBeforeBecomesAList() throws {
        let one = try JSONEncoder().encode(saved("a"))
        #expect(ServerList.decoding(one)?.active == saved("a"))
        var list = ServerList()
        list.add(saved("a")); list.add(saved("b")); list.activate(saved("a").key)
        #expect(ServerList.decoding(try JSONEncoder().encode(list)) == list)
        #expect(ServerList.decoding(Data("junk".utf8)) == nil)
    }
}

struct TransportErrorTests {
    @Test func unreachableServersAndCancelledRequests() {
        #expect((TransportError.from(URLError(.notConnectedToInternet)) as? TransportError)?.unreachable == true)
        #expect((TransportError.from(URLError(.timedOut)) as? TransportError)?.unreachable == true)
        #expect((TransportError.from(URLError(.cannotFindHost)) as? TransportError)?.unreachable == true)
        #expect((TransportError.from(URLError(.badServerResponse)) as? TransportError)?.unreachable == false)
        #expect(TransportError.from(URLError(.cancelled)) is CancellationError, "the caller moved on: not a network error")
        #expect(TransportError.from(CancellationError()) is CancellationError)
    }
}

struct ErrorTextTests {
    @Test func ownErrorsSayTheirDescriptionOthersTheirLocalizedOne() {
        #expect(ErrorText.of(TransportError(underlying: "timed out")) == "Network error: timed out")
        #expect(ErrorText.of(URLError(.badURL)) == URLError(.badURL).localizedDescription)
    }
}
