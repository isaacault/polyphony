import Foundation
import Testing
@testable import PolyphonyKit

private let base = URL(string: "https://continuo.ault.me")!

private actor MemoryStore: SessionStore {
    var saved: ContinuoSession?
    func load() async -> ContinuoSession? { saved }
    func save(_ session: ContinuoSession?) async { saved = session }
}

private func session(access: String, refresh: String, expiresIn: Int = 3600) -> Data {
    Data(#"{"accessToken":"\#(access)","expiresIn":\#(expiresIn),"refreshToken":"\#(refresh)","user":{"id":1,"username":"isaac","role":"admin"},"device":{"id":"dev-1","name":"Mac"},"library":{"type":"subsonic","baseUrl":"https://continuo.ault.me","username":"isaac","password":"device-secret"}}"#.utf8)
}

struct ContinuoTests {
    @Test func signingInSendsTheDeviceAndReturnsTheLibraryLogin() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: fixture("login")) }
        let s = try await ContinuoClient(baseURL: base, transport: fake)
            .login(username: "isaac", password: "pw", deviceName: "Isaac's Mac", platform: "macos")
        let request = try #require(fake.calls.first)
        #expect(request.method == "POST" && request.url.path == "/v1/auth/login")
        let body = try JSONSerialization.jsonObject(with: try #require(request.body)) as? [String: Any]
        #expect(body?["username"] as? String == "isaac")
        #expect((body?["device"] as? [String: String])?["platform"] == "macos")
        #expect(s.user.username == "isaac" && s.device.id == "dev-1")
        let creds = try s.subsonicCredentials(salt: "abc")
        #expect(creds.baseURL.absoluteString == "https://continuo.ault.me" && creds.password == "device-secret")
    }

    @Test func continuoErrorsKeepTheirCodeAndMessage() async {
        let fake = FakeTransport { _ in
            HTTPResponse(status: 429, body: Data(#"{"error":{"code":"rate_limited","message":"Too many failed sign-ins.","retryable":true}}"#.utf8))
        }
        await #expect(throws: ContinuoError(status: 429, code: "rate_limited", message: "Too many failed sign-ins.", retryable: true)) {
            _ = try await ContinuoClient(baseURL: base, transport: fake).login(username: "a", password: "b", deviceName: "c", platform: "macos")
        }
    }

    @Test func aFreshTokenIsUsedAsIs() async throws {
        let fake = FakeTransport { _ in HTTPResponse(status: 200, body: session(access: "acc-1", refresh: "ref-1")) }
        let account = ContinuoAccount(client: ContinuoClient(baseURL: base, transport: fake), store: MemoryStore())
        _ = try await account.signIn(username: "isaac", password: "pw", deviceName: "Mac", platform: "macos")
        #expect(try await account.accessToken() == "acc-1")
        #expect(fake.calls.count == 1, "no refresh for a token with an hour left")
    }

    @Test func anExpiringTokenIsRefreshedOnceForEveryoneWaiting() async throws {
        let fake = FakeTransport { request in
            request.url.path == "/v1/auth/refresh"
                ? HTTPResponse(status: 200, body: session(access: "acc-2", refresh: "ref-2"))
                : HTTPResponse(status: 200, body: session(access: "acc-1", refresh: "ref-1", expiresIn: 30))
        }
        let store = MemoryStore()
        let account = ContinuoAccount(client: ContinuoClient(baseURL: base, transport: fake), store: store)
        _ = try await account.signIn(username: "isaac", password: "pw", deviceName: "Mac", platform: "macos")
        async let a = account.accessToken()
        async let b = account.accessToken()
        async let c = account.accessToken()
        #expect(try await [a, b, c] == ["acc-2", "acc-2", "acc-2"])
        #expect(fake.calls.filter { $0.url.path == "/v1/auth/refresh" }.count == 1, "one refresh: continuo rotates refresh tokens")
        #expect(await store.saved?.refreshToken == "ref-2")
    }

    @Test func aRefusedRefreshEndsTheSession() async throws {
        let fake = FakeTransport { request in
            request.url.path == "/v1/auth/refresh"
                ? HTTPResponse(status: 401, body: Data(#"{"error":{"code":"refresh_token_reused","message":"Please sign in again.","retryable":false}}"#.utf8))
                : HTTPResponse(status: 200, body: session(access: "acc-1", refresh: "ref-1", expiresIn: 30))
        }
        let store = MemoryStore()
        let account = ContinuoAccount(client: ContinuoClient(baseURL: base, transport: fake), store: store)
        _ = try await account.signIn(username: "isaac", password: "pw", deviceName: "Mac", platform: "macos")
        do {
            _ = try await account.accessToken()
            Issue.record("expected sign-in to be needed")
        } catch let error as ContinuoError {
            #expect(error.needsSignIn)
        }
        let saved = await store.saved
        #expect(await account.current == nil && saved == nil)
    }

    @Test func theSpeakersAddressComesWithTheSession() throws {
        let with = Data(#"{"accessToken":"a","expiresIn":3600,"refreshToken":"r","user":{"id":1,"username":"i","role":"admin"},"device":{"id":"d","name":"Mac"},"library":{"type":"subsonic","baseUrl":"https://c","username":"i","password":"p","receiverBaseUrl":"http://192.168.0.101"}}"#.utf8)
        #expect(try JSONDecoder().decode(ContinuoSession.self, from: with).library.receiverBaseUrl == "http://192.168.0.101")
        #expect(try JSONDecoder().decode(ContinuoSession.self, from: fixture("login")).library.receiverBaseUrl == nil)
    }

    @Test func aSavedSessionKeepsItsExpiry() throws {
        var s = try JSONDecoder().decode(ContinuoSession.self, from: fixture("login"))
        s.expiresAt = Date(timeIntervalSince1970: 2_000_000_000)
        let again = try JSONDecoder().decode(ContinuoSession.self, from: JSONEncoder().encode(s))
        #expect(again.expiresAt == s.expiresAt)
    }
}
