import Foundation

public enum SubsonicError: Error, Sendable, Equatable, CustomStringConvertible {
    /// The server answered `status: failed` (40 = wrong username or password, 70 = not found…).
    case failed(code: Int, message: String)
    /// Not a Subsonic answer at all: a proxy's error page, a 5xx.
    case http(status: Int)
    case decoding(String)

    public var description: String {
        switch self {
        case let .failed(code, message): "Server error \(code): \(message)"
        case let .http(status): "The server answered HTTP \(status)"
        case let .decoding(detail): "Unexpected answer from the server: \(detail)"
        }
    }

    public var isAuthFailure: Bool {
        if case let .failed(code, _) = self { return code == 40 || code == 41 || code == 44 }
        return false
    }
}

/// The Subsonic / OpenSubsonic API, as Navidrome (directly or through continuo's façade) serves it.
public struct SubsonicClient: Sendable {
    public let credentials: SubsonicCredentials
    private let transport: HTTPTransport
    private let urls: SubsonicURLBuilder

    public init(credentials: SubsonicCredentials, transport: HTTPTransport = URLSessionTransport()) {
        self.credentials = credentials
        self.transport = transport
        self.urls = SubsonicURLBuilder(credentials: credentials)
    }

    // MARK: Library

    /// Checks the address and credentials.
    public func ping() async throws {
        _ = try await call("ping", payload: nil, as: Empty.self)
    }

    public func artists() async throws -> [Artist] {
        let dto = try await call("getArtists", payload: "artists", as: ArtistsDTO.self)
        return (dto?.index ?? []).flatMap { $0.artist ?? [] }.map(\.model)
    }

    /// The artist and their albums.
    public func artist(id: String) async throws -> (Artist, [Album]) {
        guard let dto = try await call("getArtist", ["id": id], payload: "artist", as: ArtistDTO.self) else {
            throw SubsonicError.decoding("getArtist had no artist")
        }
        return (dto.model, (dto.album ?? []).map(\.model))
    }

    /// The album with its tracks.
    public func album(id: String) async throws -> Album {
        guard let dto = try await call("getAlbum", ["id": id], payload: "album", as: AlbumDTO.self) else {
            throw SubsonicError.decoding("getAlbum had no album")
        }
        return dto.model
    }

    public enum AlbumListType: String, Sendable {
        case newest, recent, frequent, random, starred
        case alphabeticalByName, alphabeticalByArtist
    }

    public func albums(_ type: AlbumListType, size: Int = 50, offset: Int = 0) async throws -> [Album] {
        let dto = try await call("getAlbumList2", ["type": type.rawValue, "size": "\(size)", "offset": "\(offset)"],
                                 payload: "albumList2", as: AlbumListDTO.self)
        return (dto?.album ?? []).map(\.model)
    }

    public func search(_ query: String, artists: Int = 10, albums: Int = 20, tracks: Int = 30) async throws -> SearchResults {
        let dto = try await call("search3", ["query": query, "artistCount": "\(artists)",
                                             "albumCount": "\(albums)", "songCount": "\(tracks)"],
                                 payload: "searchResult3", as: SearchResult3DTO.self)
        return SearchResults(artists: (dto?.artist ?? []).map(\.model), albums: (dto?.album ?? []).map(\.model),
                             tracks: (dto?.song ?? []).map(\.model))
    }

    // MARK: URLs the player and image views load themselves

    /// The audio, transcoded when [maxBitRateKbps] or [format] ask for it (the server's own choice when nil).
    public func streamURL(trackID: String, maxBitRateKbps: Int? = nil, format: String? = nil) -> URL {
        var params = [("id", trackID)]
        if let maxBitRateKbps { params.append(("maxBitRate", "\(maxBitRateKbps)")) }
        if let format { params.append(("format", format)) }
        return urls.url("stream", params)
    }

    public func coverArtURL(id: String, size: Int? = nil) -> URL {
        var params = [("id", id)]
        if let size { params.append(("size", "\(size)")) }
        return urls.url("getCoverArt", params)
    }

    // MARK: Plumbing

    private struct Empty: Decodable {}

    private func call<T: Decodable>(_ endpoint: String, _ params: [String: String] = [:], payload key: String?,
                                    as type: T.Type) async throws -> T? {
        let url = urls.url(endpoint, params.sorted { $0.key < $1.key }.map { ($0.key, $0.value) })
        let response = try await transport.send(HTTPRequest(url: url))
        return try SubsonicEnvelope.decode(response, payload: key, as: type)
    }
}

/// `{"subsonic-response": {"status": "ok", "<payload>": {...}}}`, or `status: failed` with an error.
enum SubsonicEnvelope {
    private struct Outer<Inner: Decodable>: Decodable {
        let response: Inner
        enum CodingKeys: String, CodingKey { case response = "subsonic-response" }
    }

    private struct Status: Decodable {
        let status: String
        let error: SubsonicErrorDTO?
    }

    private struct Payload<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws {
            let key = decoder.userInfo[SubsonicEnvelope.payloadKey] as! String
            let container = try decoder.container(keyedBy: AnyKey.self)
            value = try container.decodeIfPresent(T.self, forKey: AnyKey(key))
        }
    }

    private struct AnyKey: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ string: String) { stringValue = string }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    static let payloadKey = CodingUserInfoKey(rawValue: "subsonicPayload")!

    static func decode<T: Decodable>(_ response: HTTPResponse, payload key: String?, as type: T.Type) throws -> T? {
        let status: Status
        do {
            status = try JSONDecoder().decode(Outer<Status>.self, from: response.body).response
        } catch {
            // continuo's façade answers a bad credential with HTTP 401 *and* a Subsonic error body,
            // so the body is read first; only a body that isn't Subsonic at all is an HTTP error.
            if !(200..<300).contains(response.status) { throw SubsonicError.http(status: response.status) }
            throw SubsonicError.decoding(String(describing: error))
        }
        guard status.status == "ok" else {
            throw SubsonicError.failed(code: status.error?.code ?? 0, message: status.error?.message ?? "unknown error")
        }
        guard let key else { return nil }
        let decoder = JSONDecoder()
        decoder.userInfo[payloadKey] = key
        do {
            return try decoder.decode(Outer<Payload<T>>.self, from: response.body).response.value
        } catch {
            throw SubsonicError.decoding(String(describing: error))
        }
    }
}
