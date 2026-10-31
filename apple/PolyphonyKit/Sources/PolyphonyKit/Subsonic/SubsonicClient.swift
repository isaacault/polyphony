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
        /// The user's top-rated albums (ratings mode's "Top rated").
        case highest
        case alphabeticalByName, alphabeticalByArtist
    }

    public func albums(_ type: AlbumListType, size: Int = 50, offset: Int = 0) async throws -> [Album] {
        let dto = try await call("getAlbumList2", ["type": type.rawValue, "size": "\(size)", "offset": "\(offset)"],
                                 payload: "albumList2", as: AlbumListDTO.self)
        return (dto?.album ?? []).map(\.model)
    }

    /// [query] "" lists everything (Navidrome), a page at a time: how the Songs list is read.
    public func search(_ query: String, artists: Int = 10, albums: Int = 20, tracks: Int = 30,
                       trackOffset: Int = 0) async throws -> SearchResults {
        let dto = try await call("search3", ["query": query, "artistCount": "\(artists)",
                                             "albumCount": "\(albums)", "songCount": "\(tracks)",
                                             "songOffset": "\(trackOffset)"],
                                 payload: "searchResult3", as: SearchResult3DTO.self)
        return SearchResults(artists: (dto?.artist ?? []).map(\.model), albums: (dto?.album ?? []).map(\.model),
                             tracks: (dto?.song ?? []).map(\.model))
    }

    public func songs(_ type: SongListType, size: Int = 100) async throws -> [Track] {
        switch type {
        case .random:
            let dto = try await call("getRandomSongs", ["size": "\(size)"], payload: "randomSongs", as: SongsDTO.self)
            return (dto?.song ?? []).map(\.model)
        case let .genre(name):
            let dto = try await call("getSongsByGenre", ["genre": name, "count": "\(size)"], payload: "songsByGenre",
                                     as: SongsDTO.self)
            return (dto?.song ?? []).map(\.model)
        }
    }

    public enum SongListType: Sendable, Hashable { case random, genre(String) }

    public func albums(genre: String, size: Int = 100, offset: Int = 0) async throws -> [Album] {
        let dto = try await call("getAlbumList2", ["type": "byGenre", "genre": genre, "size": "\(size)", "offset": "\(offset)"],
                                 payload: "albumList2", as: AlbumListDTO.self)
        return (dto?.album ?? []).map(\.model)
    }

    public func genres() async throws -> [Genre] {
        let dto = try await call("getGenres", payload: "genres", as: GenresDTO.self)
        return (dto?.genre ?? []).compactMap { g in
            guard let name = g.value, !name.isEmpty else { return nil }
            return Genre(name: name, songCount: g.songCount ?? 0, albumCount: g.albumCount ?? 0)
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    public func artistInfo(id: String) async throws -> ArtistInfo {
        let dto = try await call("getArtistInfo2", ["id": id], payload: "artistInfo2", as: ArtistInfoDTO.self)
        return ArtistInfo(biography: dto?.biography.flatMap { $0.isEmpty ? nil : $0 },
                          imageURL: dto?.largeImageUrl.flatMap { $0.isEmpty ? nil : URL(string: $0) },
                          similarArtists: (dto?.similarArtist ?? []).map(\.model))
    }

    public func starred() async throws -> Starred {
        let dto = try await call("getStarred2", payload: "starred2", as: SearchResult3DTO.self)
        return Starred(artists: (dto?.artist ?? []).map(\.model), albums: (dto?.album ?? []).map(\.model),
                       tracks: (dto?.song ?? []).map(\.model))
    }

    public enum Item: Sendable, Hashable { case track(String), album(String), artist(String) }

    /// Favourite (or not) a song, album or artist.
    public func setStarred(_ item: Item, _ starred: Bool) async throws {
        let param: (String, String) = switch item {
        case let .track(id): ("id", id)
        case let .album(id): ("albumId", id)
        case let .artist(id): ("artistId", id)
        }
        _ = try await call(starred ? "star" : "unstar", [param], payload: nil, as: Empty.self)
    }

    /// 0 clears the rating; 1-5 stars.
    public func setRating(id: String, _ rating: Int) async throws {
        _ = try await call("setRating", ["id": id, "rating": "\(min(max(rating, 0), 5))"], payload: nil, as: Empty.self)
    }

    /// "Now playing" ([submission] false) or a play to count ([submission] true, after half the song
    /// or 4 minutes, as Last.fm counts). Navidrome forwards it to Last.fm and ListenBrainz.
    public func scrobble(trackID: String, at time: Date = Date(), submission: Bool) async throws {
        _ = try await call("scrobble", ["id": trackID, "time": "\(Int64(time.timeIntervalSince1970 * 1000))",
                                        "submission": submission ? "true" : "false"], payload: nil, as: Empty.self)
    }

    // MARK: Playlists

    public func playlists() async throws -> [Playlist] {
        let dto = try await call("getPlaylists", payload: "playlists", as: PlaylistsDTO.self)
        return (dto?.playlist ?? []).map(\.model)
    }

    public func playlist(id: String) async throws -> Playlist {
        guard let dto = try await call("getPlaylist", ["id": id], payload: "playlist", as: PlaylistDTO.self) else {
            throw SubsonicError.decoding("getPlaylist had no playlist")
        }
        return dto.model
    }

    @discardableResult
    public func createPlaylist(name: String, trackIDs: [String] = []) async throws -> Playlist? {
        try await call("createPlaylist", [("name", name)] + trackIDs.map { ("songId", $0) }, payload: "playlist",
                       as: PlaylistDTO.self)?.model
    }

    public func updatePlaylist(id: String, name: String? = nil, isPublic: Bool? = nil, adding: [String] = [],
                               removingIndices: [Int] = []) async throws {
        var params = [("playlistId", id)]
        if let name { params.append(("name", name)) }
        if let isPublic { params.append(("public", isPublic ? "true" : "false")) }
        params += adding.map { ("songIdToAdd", $0) }
        params += removingIndices.map { ("songIndexToRemove", "\($0)") }
        _ = try await call("updatePlaylist", params, payload: nil, as: Empty.self)
    }

    public func deletePlaylist(id: String) async throws {
        _ = try await call("deletePlaylist", ["id": id], payload: nil, as: Empty.self)
    }

    // MARK: Lyrics

    /// The file's own lyrics (OpenSubsonic `getLyricsBySongId`, which Navidrome serves): synced when
    /// the file has timings. Nil when the song has none or the server lacks the call.
    public func lyrics(trackID: String) async throws -> Lyrics? {
        let dto: LyricsListDTO?
        do {
            dto = try await call("getLyricsBySongId", ["id": trackID], payload: "lyricsList", as: LyricsListDTO.self)
        } catch SubsonicError.failed(code: 0, _), SubsonicError.failed(code: 70, _), SubsonicError.http(status: 404) {
            return nil
        }
        let all = dto?.structuredLyrics ?? []
        guard let best = all.first(where: { $0.synced == true && !($0.line ?? []).isEmpty })
            ?? all.first(where: { !($0.line ?? []).isEmpty }) else { return nil }
        let synced = best.synced ?? false
        return Lyrics(lines: (best.line ?? []).map { LyricLine(startMs: synced ? $0.start : nil, text: $0.value ?? "") },
                      synced: synced, instrumental: false, source: nil)
    }

    // MARK: URLs the player and image views load themselves

    /// The audio, transcoded when [maxBitRateKbps] or [format] ask for it (the server's own choice when nil).
    public func streamURL(trackID: String, maxBitRateKbps: Int? = nil, format: String? = nil) -> URL {
        var params = [("id", trackID)]
        if let maxBitRateKbps { params.append(("maxBitRate", "\(maxBitRateKbps)")) }
        if let format { params.append(("format", format)) }
        return urls.url("stream", params)
    }

    /// The original file, untranscoded: what a download keeps.
    public func downloadURL(trackID: String) -> URL {
        urls.url("download", [("id", trackID)])
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
        try await call(endpoint, params.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }, payload: key, as: type)
    }

    /// For repeated parameters (`songId=1&songId=2`).
    private func call<T: Decodable>(_ endpoint: String, _ params: [(String, String)], payload key: String?,
                                    as type: T.Type) async throws -> T? {
        let response = try await transport.send(HTTPRequest(url: urls.url(endpoint, params)))
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
