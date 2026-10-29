import Foundation

/// A server the app is signed in to, as kept between launches (in the Keychain on Apple).
public struct SavedServer: Sendable, Codable, Equatable {
    public enum Kind: String, Sendable, Codable {
        /// continuo: search, requests and Play now, with the library through its Subsonic façade.
        case continuo
        /// A plain Subsonic server (Navidrome, …): the library only.
        case subsonic
    }

    public var kind: Kind
    public var address: URL
    /// The library login: continuo's per-device credential, or the user's own for plain Subsonic.
    public var subsonicUsername: String
    public var subsonicPassword: String
    public var salt: String
    public var session: ContinuoSession?

    public var subsonic: SubsonicCredentials {
        SubsonicCredentials(baseURL: kind == .continuo
                                ? ((try? ServerAddress.normalise(session?.library.baseUrl ?? "")) ?? address) : address,
                            username: subsonicUsername, password: subsonicPassword, salt: salt)
    }

    public var displayName: String {
        "\(subsonicUsername) on \(address.host ?? address.absoluteString)"
    }
}

extension SavedServer {
    /// Which server this is, across sign-ins: kind, address and user.
    public var key: String { "\(kind.rawValue)|\(address.absoluteString)|\(subsonicUsername)" }
}

/// Every server the app is signed in to, and which one it's showing (the Android app's server list).
public struct ServerList: Sendable, Codable, Equatable {
    public private(set) var servers: [SavedServer] = []
    public private(set) var activeKey: String?

    public init(servers: [SavedServer] = [], activeKey: String? = nil) {
        self.servers = servers
        self.activeKey = activeKey ?? servers.first?.key
    }

    public var active: SavedServer? { servers.first { $0.key == activeKey } }

    public func server(_ key: String) -> SavedServer? { servers.first { $0.key == key } }

    /// Signing in: a server already here is replaced where it is; either way it becomes the active one.
    public mutating func add(_ server: SavedServer) {
        if let i = servers.firstIndex(where: { $0.key == server.key }) { servers[i] = server } else { servers.append(server) }
        activeKey = server.key
    }

    /// A server's newer state (a refreshed session), without switching to it.
    public mutating func update(_ server: SavedServer) {
        guard let i = servers.firstIndex(where: { $0.key == server.key }) else { return }
        servers[i] = server
    }

    @discardableResult
    public mutating func activate(_ key: String) -> Bool {
        guard server(key) != nil else { return false }
        activeKey = key
        return true
    }

    /// Signed out of [key]: if it was showing, the next one left takes over (nil: none left).
    public mutating func remove(_ key: String) {
        servers.removeAll { $0.key == key }
        if activeKey == key || active == nil { activeKey = servers.first?.key }
    }

    /// What the Keychain holds: a list, or one server from before there were several.
    public static func decoding(_ data: Data) -> ServerList? {
        if let list = try? JSONDecoder().decode(ServerList.self, from: data) { return list }
        return (try? JSONDecoder().decode(SavedServer.self, from: data)).map { ServerList(servers: [$0]) }
    }
}

public protocol ServerStore: Sendable {
    func load() async -> ServerList
    func save(_ list: ServerList) async
}

/// continuo's refreshed sessions go back into their own saved server (not whichever is showing), so
/// a restart signs in silently.
public struct ServerSessionStore: SessionStore {
    let store: ServerStore
    let key: String

    public init(_ store: ServerStore, key: String) {
        self.store = store
        self.key = key
    }

    public func load() async -> ContinuoSession? {
        await store.load().server(key)?.session
    }

    public func save(_ session: ContinuoSession?) async {
        var list = await store.load()
        guard var server = list.server(key) else { return }
        guard let session else {
            // The session ended (signed out elsewhere): the server goes with it.
            list.remove(key)
            await store.save(list)
            return
        }
        server.session = session
        server.subsonicUsername = session.library.username
        server.subsonicPassword = session.library.password
        list.update(server)
        await store.save(list)
    }
}

public enum ConnectError: Error, Sendable, Equatable, CustomStringConvertible {
    case badAddress
    case wrongPassword
    case unreachable(String)
    case notAMusicServer
    case server(String)

    public var description: String {
        switch self {
        case .badAddress: "That doesn't look like a server address."
        case .wrongPassword: "Wrong username or password."
        case let .unreachable(detail): "Couldn't reach the server (\(detail))."
        case .notAMusicServer: "There's no continuo or Subsonic server at that address."
        case let .server(message): message
        }
    }
}

public enum Connector {
    /// Signs in at [address]: as continuo when it is one, else as a plain Subsonic server. A wrong
    /// password at a continuo is a wrong password, never a fall back to Subsonic.
    public static func connect(address: String, username: String, password: String, deviceName: String,
                               platform: String = "macos", transport: HTTPTransport = URLSessionTransport()) async throws -> SavedServer {
        guard let url = try? ServerAddress.normalise(address) else { throw ConnectError.badAddress }
        let continuo = ContinuoClient(baseURL: url, transport: transport)
        do {
            let session = try await continuo.login(username: username, password: password, deviceName: deviceName,
                                                   platform: platform)
            return SavedServer(kind: .continuo, address: url, subsonicUsername: session.library.username,
                               subsonicPassword: session.library.password, salt: SubsonicCredentials.newSalt(),
                               session: session)
        } catch let error as ContinuoError {
            // Not continuo (no such route, or a page that isn't continuo's JSON): try Subsonic.
            let notContinuo = error.status == 404 || error.status == 405 || error.code == "unexpected_response"
                || error.code.hasPrefix("http_")
            if error.status == 401 { throw ConnectError.wrongPassword }
            if !notContinuo { throw ConnectError.server(error.message) }
        } catch let error as TransportError {
            throw ConnectError.unreachable(error.underlying)
        }

        let creds = SubsonicCredentials(baseURL: url, username: username, password: password)
        do {
            try await SubsonicClient(credentials: creds, transport: transport).ping()
        } catch let error as SubsonicError {
            if error.isAuthFailure { throw ConnectError.wrongPassword }
            throw ConnectError.notAMusicServer
        } catch let error as TransportError {
            throw ConnectError.unreachable(error.underlying)
        }
        return SavedServer(kind: .subsonic, address: url, subsonicUsername: username, subsonicPassword: password,
                           salt: creds.salt, session: nil)
    }
}
