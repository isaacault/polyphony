import Foundation

/// continuo's error body: `{"error": {"code", "message", "retryable"}}`.
public struct ContinuoError: Error, Sendable, Equatable, CustomStringConvertible {
    public let status: Int
    public let code: String
    public let message: String
    public let retryable: Bool

    public var description: String { message }

    /// The session is over (signed out elsewhere, refresh token expired or reused): sign in again. An
    /// expired *access* token (`invalid_token`) isn't this: [ContinuoAccount] refreshes it.
    public var needsSignIn: Bool {
        status == 401 && ["session_expired", "device_revoked", "invalid_refresh_token", "refresh_token_reused"].contains(code)
    }
}

public struct ContinuoSession: Sendable, Codable, Equatable {
    public struct User: Sendable, Codable, Equatable {
        public let id: Int
        public let username: String
        public let role: String
    }

    public struct Device: Sendable, Codable, Equatable {
        public let id: String
        public let name: String
    }

    /// The Subsonic login continuo hands this device: its façade on continuo's own address.
    public struct Library: Sendable, Codable, Equatable {
        public let type: String
        public let baseUrl: String
        public let username: String
        public let password: String
    }

    public let accessToken: String
    public let expiresIn: Int
    public let refreshToken: String
    public let user: User
    public let device: Device
    public let library: Library
    /// When [accessToken] stops working; set from [expiresIn] when the session arrives.
    public var expiresAt: Date

    enum CodingKeys: String, CodingKey {
        case accessToken, expiresIn, refreshToken, user, device, library, expiresAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accessToken = try c.decode(String.self, forKey: .accessToken)
        expiresIn = try c.decode(Int.self, forKey: .expiresIn)
        refreshToken = try c.decode(String.self, forKey: .refreshToken)
        user = try c.decode(User.self, forKey: .user)
        device = try c.decode(Device.self, forKey: .device)
        library = try c.decode(Library.self, forKey: .library)
        expiresAt = try c.decodeIfPresent(Date.self, forKey: .expiresAt) ?? Date().addingTimeInterval(TimeInterval(expiresIn))
    }

    /// The Subsonic credentials for this device, keeping [salt] stable across launches.
    public func subsonicCredentials(salt: String) throws -> SubsonicCredentials {
        SubsonicCredentials(baseURL: try ServerAddress.normalise(library.baseUrl), username: library.username,
                            password: library.password, salt: salt)
    }
}

/// continuo's own API (`/v1/...`): sign-in, and later search, requests and Play now. Stateless: the
/// tokens live in [ContinuoAccount].
public struct ContinuoClient: Sendable {
    public let baseURL: URL
    private let transport: HTTPTransport

    public init(baseURL: URL, transport: HTTPTransport = URLSessionTransport()) {
        self.baseURL = baseURL
        self.transport = transport
    }

    public func login(username: String, password: String, deviceName: String, platform: String) async throws -> ContinuoSession {
        struct Body: Encodable {
            struct Device: Encodable { let name: String; let platform: String }
            let username: String
            let password: String
            let device: Device
        }
        return try await send("POST", "v1/auth/login",
                              body: Body(username: username, password: password,
                                         device: .init(name: deviceName, platform: platform)))
    }

    public func refresh(refreshToken: String) async throws -> ContinuoSession {
        struct Body: Encodable { let refreshToken: String }
        return try await send("POST", "v1/auth/refresh", body: Body(refreshToken: refreshToken))
    }

    public struct Me: Sendable, Decodable, Equatable {
        public let id: Int
        public let username: String
        public let role: String
    }

    public func me(accessToken: String) async throws -> Me {
        try await send("GET", "v1/me", token: accessToken)
    }

    // MARK: Plumbing

    private struct NoBody: Encodable {}

    func send<T: Decodable>(_ method: String, _ path: String, token: String? = nil) async throws -> T {
        try await send(method, path, body: Optional<NoBody>.none, token: token)
    }

    func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, token: String? = nil) async throws -> T {
        var request = HTTPRequest(method: method, url: baseURL.appendingPathComponent(path),
                                  headers: ["Accept": "application/json"])
        if let body {
            request.body = try JSONEncoder().encode(body)
            request.headers["Content-Type"] = "application/json"
        }
        if let token { request.headers["Authorization"] = "Bearer \(token)" }
        let response = try await transport.send(request)
        guard (200..<300).contains(response.status) else { throw Self.error(from: response) }
        do {
            return try JSONDecoder().decode(T.self, from: response.body)
        } catch {
            throw ContinuoError(status: response.status, code: "unexpected_response",
                                message: "Unexpected answer from continuo.", retryable: false)
        }
    }

    static func error(from response: HTTPResponse) -> ContinuoError {
        struct Body: Decodable {
            struct Inner: Decodable { let code: String; let message: String; let retryable: Bool? }
            let error: Inner
        }
        if let body = try? JSONDecoder().decode(Body.self, from: response.body) {
            return ContinuoError(status: response.status, code: body.error.code, message: body.error.message,
                                 retryable: body.error.retryable ?? false)
        }
        return ContinuoError(status: response.status, code: "http_\(response.status)",
                             message: "continuo answered HTTP \(response.status).", retryable: response.status >= 500)
    }
}

/// Where a session is kept between launches: the Keychain in the app, memory in tests.
public protocol SessionStore: Sendable {
    func load() async -> ContinuoSession?
    func save(_ session: ContinuoSession?) async
}

/// One signed-in continuo account: hands out a current access token, refreshing it shortly before it
/// expires. Concurrent callers share one refresh: continuo rotates the refresh token on every use, so
/// two refreshes racing would leave one of them holding a token that no longer works.
public actor ContinuoAccount {
    public let client: ContinuoClient
    private let store: SessionStore
    private let now: @Sendable () -> Date
    private var session: ContinuoSession?
    private var refreshing: Task<ContinuoSession, Error>?
    /// Refresh this long before the token expires, so a request doesn't leave with one about to die.
    private let margin: TimeInterval = 60

    public init(client: ContinuoClient, store: SessionStore, now: @escaping @Sendable () -> Date = Date.init) {
        self.client = client
        self.store = store
        self.now = now
    }

    public var current: ContinuoSession? { session }

    /// Restores the saved session, if any. Call once at launch.
    public func restore() async -> ContinuoSession? {
        session = await store.load()
        return session
    }

    public func signIn(username: String, password: String, deviceName: String, platform: String) async throws -> ContinuoSession {
        let s = try await client.login(username: username, password: password, deviceName: deviceName, platform: platform)
        session = s
        await store.save(s)
        return s
    }

    public func signOut() async {
        session = nil
        refreshing?.cancel()
        refreshing = nil
        await store.save(nil)
    }

    /// A token good for the next request; throws `ContinuoError` with `needsSignIn` when the session ended.
    public func accessToken() async throws -> String {
        guard let s = session else {
            throw ContinuoError(status: 401, code: "session_expired", message: "Please sign in.", retryable: false)
        }
        if s.expiresAt.timeIntervalSince(now()) > margin { return s.accessToken }
        return try await refreshed().accessToken
    }

    private func refreshed() async throws -> ContinuoSession {
        if let refreshing { return try await refreshing.value }
        guard let old = session else {
            throw ContinuoError(status: 401, code: "session_expired", message: "Please sign in.", retryable: false)
        }
        let client = self.client
        let task = Task { try await client.refresh(refreshToken: old.refreshToken) }
        refreshing = task
        defer { refreshing = nil }
        do {
            let s = try await task.value
            session = s
            await store.save(s)
            return s
        } catch let error as ContinuoError where error.status == 401 {
            // Whatever the code, a refresh that's refused means the session is gone.
            session = nil
            await store.save(nil)
            throw error
        }
    }
}
