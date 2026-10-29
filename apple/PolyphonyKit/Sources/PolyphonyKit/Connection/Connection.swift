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

public protocol ServerStore: Sendable {
    func load() async -> SavedServer?
    func save(_ server: SavedServer?) async
}

/// continuo's refreshed sessions go back into the saved server, so a restart signs in silently.
public struct ServerSessionStore: SessionStore {
    let store: ServerStore

    public init(_ store: ServerStore) {
        self.store = store
    }

    public func load() async -> ContinuoSession? {
        await store.load()?.session
    }

    public func save(_ session: ContinuoSession?) async {
        guard var server = await store.load() else { return }
        guard let session else {
            // The session ended (signed out elsewhere): the server goes with it, and the app asks again.
            await store.save(nil)
            return
        }
        server.session = session
        server.subsonicUsername = session.library.username
        server.subsonicPassword = session.library.password
        await store.save(server)
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
