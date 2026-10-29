import Foundation

/// continuo's signed-in API: search, the catalogue, requests, Play now, discovery and lyrics. Every
/// call carries the account's current token; one the server refuses is refreshed and tried once more.
public struct ContinuoService: Sendable {
    public let account: ContinuoAccount
    private var client: ContinuoClient { account.client }
    /// Catalogue art is kept only when continuo serves it itself: third-party image hosts would break
    /// "your traffic goes only to your own server" (continuo proxies catalogue art).
    private var artworkHost: String? { client.baseURL.host?.lowercased() }

    public init(account: ContinuoAccount) {
        self.account = account
    }

    // MARK: Search and the catalogue

    /// Library and catalogue together, ranked by listening. [quick] answers the first few fast, for
    /// search as you type.
    public func search(_ query: String, limit: Int = 25, quick: Bool = false) async throws -> CatalogResults {
        var items = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "types", value: "album,artist,track"),
                     URLQueryItem(name: "limit", value: "\(limit)")]
        if quick { items.append(URLQueryItem(name: "quick", value: "true")) }
        let dto: SearchDTO = try await get("v1/search", items)
        return dto.model(artworkHost)
    }

    public func catalogArtist(musicBrainzID: String) async throws -> CatalogArtistPage {
        let dto: CatalogArtistDTO = try await get("v1/catalog/artists/\(musicBrainzID)")
        return dto.model(artworkHost)
    }

    public func catalogArtistAlbums(musicBrainzID: String) async throws -> [CatalogAlbum] {
        let hits: [HitDTO] = try await get("v1/catalog/artists/\(musicBrainzID)/albums")
        return hits.map { $0.album(artworkHost) }
    }

    public func catalogAlbum(releaseGroupID: String) async throws -> CatalogAlbumPage {
        let hit: HitDTO = try await get("v1/catalog/albums/\(releaseGroupID)")
        return CatalogAlbumPage(album: hit.album(artworkHost), tracks: (hit.tracks ?? []).map(\.model))
    }

    public func discovery() async throws -> Discovery {
        let dto: DiscoverDTO = try await get("v1/discover")
        return dto.model(artworkHost)
    }

    public func discoveryPlaylist(id: String) async throws -> DiscoveryPlaylist {
        let dto: DiscoveryPlaylistPageDTO = try await get("v1/discover/playlists/\(id)")
        return DiscoveryPlaylist(id: dto.id, title: dto.title ?? "", description: dto.description,
                                 tracks: (dto.tracks ?? []).filter { $0.kind == "track" }.map { $0.track(artworkHost) })
    }

    // MARK: Requests

    public enum RequestOutcome: Sendable, Equatable {
        case requested(id: Int)
        case alreadyRequested(id: Int?)
        case inLibrary(albumID: String?)
        /// Was removed from this user's library and is back.
        case addedBack
    }

    public func requestAlbum(releaseGroupID: String) async throws -> RequestOutcome {
        try await request(RequestBody(kind: "album", musicBrainzReleaseGroupId: releaseGroupID, musicBrainzRecordingId: nil))
    }

    public func requestTrack(recordingID: String, releaseGroupID: String) async throws -> RequestOutcome {
        try await request(RequestBody(kind: "track", musicBrainzReleaseGroupId: releaseGroupID, musicBrainzRecordingId: recordingID))
    }

    public func requests(activeOnly: Bool = false) async throws -> [MusicRequest] {
        let dto: RequestListDTO = try await get("v1/requests", [URLQueryItem(name: "scope", value: activeOnly ? "active" : "all"),
                                                               URLQueryItem(name: "limit", value: "200")])
        return dto.items.map { $0.model(artworkHost) }
    }

    public enum RequestAction: String, Sendable {
        case searchNow = "search-now", retry, dropDownload = "drop-download", remove
    }

    /// What continuo says it did ("Searching again"), or "" when it said nothing.
    @discardableResult
    public func perform(_ action: RequestAction, onRequest id: Int) async throws -> String {
        let response = action == .remove
            ? try await authorised("DELETE", "v1/requests/\(id)")
            : try await authorised("POST", "v1/requests/\(id)/\(action.rawValue)", body: Empty())
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        return (try? JSONDecoder().decode(ActionResultDTO.self, from: response.body).message) ?? ""
    }

    // MARK: Play now

    /// Starts fetching a song that isn't in the library; poll [playNow(id:)] until it's playable.
    public func startPlayNow(_ track: CatalogTrack, queued: Bool = false) async throws -> PlayNowStream {
        guard let recording = track.recordingID else {
            throw ContinuoError(status: 400, code: "no_recording", message: "This song can't be played now.", retryable: false)
        }
        let body = PlayNowBody(musicBrainzRecordingId: recording, musicBrainzReleaseGroupId: track.albumReleaseGroupID,
                               title: track.title, artist: track.artist ?? "", durationMs: track.durationMs, queued: queued)
        let response = try await authorised("POST", "v1/play-now", body: body)
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        return try decode(PlayNowDTO.self, response).model
    }

    public func playNow(id: String) async throws -> PlayNowStream {
        let dto: PlayNowDTO = try await get("v1/play-now/\(id)")
        return dto.model
    }

    /// Keeps a Play now song: continuo requests it for the library in full quality.
    public func keepPlayNow(id: String) async throws {
        let response = try await authorised("POST", "v1/play-now/\(id)/keep", body: Empty())
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
    }

    /// The stream's audio; the player must send `Authorization: Bearer <token>` with it.
    public func playNowAudioURL(id: String) -> URL {
        client.baseURL.appendingPathComponent("v1/play-now/\(id)/audio")
    }

    // MARK: Library extras

    /// A song's lyrics from LRCLIB (for songs whose files have none); nil when none are known.
    public func lyrics(artist: String, title: String, album: String?, durationMs: Int?) async throws -> Lyrics? {
        var items = [URLQueryItem(name: "artist", value: artist), URLQueryItem(name: "title", value: title)]
        if let album { items.append(URLQueryItem(name: "album", value: album)) }
        if let durationMs { items.append(URLQueryItem(name: "durationMs", value: "\(durationMs)")) }
        let response = try await authorised("GET", "v1/library/lyrics", query: items)
        if response.status == 404 { return nil }
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        return try decode(LyricsDTO.self, response).model
    }

    /// Library albums that hold single songs (one song of an album): left out of album lists.
    public func singleSongAlbums() async throws -> [String: (have: Int, expected: Int)] {
        let dto: SinglesDTO = try await get("v1/library/singles")
        return Dictionary(dto.albums.map { ($0.navidromeAlbumId, (have: $0.have ?? 1, expected: $0.expected ?? 1)) },
                          uniquingKeysWith: { a, _ in a })
    }

    // MARK: Removing albums

    /// Takes an album out of the library; it can be restored for a while.
    public func removeAlbum(libraryAlbumID: String) async throws {
        let response = try await authorised("POST", "v1/library/albums/\(libraryAlbumID)/remove", body: Empty())
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
    }

    public func removedAlbums() async throws -> [RemovedAlbum] {
        let dtos: [RemovedDTO] = try await get("v1/library/removed")
        return dtos.map { RemovedAlbum(id: $0.id, title: $0.title ?? "", artist: $0.artist, removedAt: $0.removedAt,
                                       restorableUntil: $0.restorableUntil) }
    }

    public func restoreAlbum(removedID: Int) async throws {
        let response = try await authorised("POST", "v1/library/removed/\(removedID)/restore", body: Empty())
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
    }

    // MARK: Scrobbling for Play now songs

    /// Nil when this continuo predates scrobbling.
    public func scrobbling() async throws -> ScrobbleLinks? {
        let response = try await authorised("GET", "v1/scrobbling")
        if response.status == 404 { return nil }
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        return try decode(ScrobbleLinksDTO.self, response).model
    }

    public func linkListenBrainz(token: String) async throws -> ScrobbleLinks {
        try await scrobbleCall("PUT", "v1/scrobbling/listenbrainz", body: TokenBody(token: token.trimmingCharacters(in: .whitespaces)))
    }

    /// Last.fm: open [url] for the user to approve, then call [finishLastFm] with [token].
    public func startLastFm() async throws -> (token: String, url: URL) {
        let response = try await authorised("POST", "v1/scrobbling/lastfm/connect", body: Empty())
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        let dto = try decode(LastFmStartDTO.self, response)
        guard let url = URL(string: dto.url) else {
            throw ContinuoError(status: response.status, code: "unexpected_response", message: "Last.fm gave no address.",
                                retryable: false)
        }
        return (dto.token, url)
    }

    public func finishLastFm(token: String) async throws -> ScrobbleLinks {
        try await scrobbleCall("POST", "v1/scrobbling/lastfm/finish", body: TokenBody(token: token))
    }

    public func unlinkScrobbler(_ service: String) async throws -> ScrobbleLinks {
        try await scrobbleCall("DELETE", "v1/scrobbling/\(service)", body: Optional<Empty>.none)
    }

    private func scrobbleCall<B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> ScrobbleLinks {
        let response = try await authorised(method, path, body: body)
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        return try decode(ScrobbleLinksDTO.self, response).model
    }

    // MARK: Devices

    public func devices() async throws -> [SignedInDevice] {
        let dtos: [DeviceDTO] = try await get("v1/devices")
        return dtos.map { SignedInDevice(id: $0.id, name: $0.name ?? "Device", platform: $0.platform,
                                         lastSeenAt: $0.lastSeenAt, current: $0.current ?? false) }
    }

    /// Signs a device out (its tokens and its library login stop working).
    public func signOut(device id: String) async throws {
        let response = try await authorised("DELETE", "v1/devices/\(id)")
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
    }

    public func signOutOtherDevices() async throws {
        let response = try await authorised("POST", "v1/devices/revoke-others", body: Empty())
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
    }

    // MARK: Plumbing

    private struct Empty: Encodable {}

    private func request(_ body: RequestBody) async throws -> RequestOutcome {
        let response = try await authorised("POST", "v1/requests", body: body)
        if (200..<300).contains(response.status) {
            return .requested(id: try decode(CreatedDTO.self, response).id)
        }
        if response.status == 409, let err = try? JSONDecoder().decode(ConflictDTO.self, from: response.body).error {
            switch err.code {
            case "in_library": return .inLibrary(albumID: err.navidromeAlbumId)
            case "added_to_library": return .addedBack
            case "already_requested": return .alreadyRequested(id: err.requestId)
            default: break
            }
        }
        throw ContinuoClient.error(from: response)
    }

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        let response = try await authorised("GET", path, query: query)
        guard (200..<300).contains(response.status) else { throw ContinuoClient.error(from: response) }
        return try decode(T.self, response)
    }

    private func decode<T: Decodable>(_ type: T.Type, _ response: HTTPResponse) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: response.body)
        } catch {
            throw ContinuoError(status: response.status, code: "unexpected_response",
                                message: "Unexpected answer from continuo.", retryable: false)
        }
    }

    private func authorised(_ method: String, _ path: String, query: [URLQueryItem] = []) async throws -> HTTPResponse {
        try await authorised(method, path, query: query, body: Optional<Empty>.none)
    }

    private func authorised<B: Encodable>(_ method: String, _ path: String, query: [URLQueryItem] = [],
                                          body: B?) async throws -> HTTPResponse {
        let response = try await client.raw(method, path, query: query, body: body, token: try await account.accessToken())
        guard response.status == 401, ContinuoClient.error(from: response).code == "invalid_token" else { return response }
        return try await client.raw(method, path, query: query, body: body, token: try await account.refreshNow())
    }
}

// MARK: - Wire shapes

private struct ArtworkDTO: Decodable { let url: String? }

private struct IdsDTO: Decodable {
    let musicBrainzReleaseGroupId: String?
    let navidromeAlbumId: String?
    let musicBrainzArtistId: String?
}

private struct AlbumRefDTO: Decodable {
    let title: String?
    let musicBrainzReleaseGroupId: String?
    let navidromeAlbumId: String?
}

private struct AvailabilityDTO: Decodable {
    let state: String?
    let navidromeAlbumId: String?
    let requestId: Int?
    let stillLooking: Bool?
    let have: Int?
    let expected: Int?
    let storeAlbumId: String?
    let requestable: Bool?

    func model(fallbackAlbumID: String?, default fallback: String = "requestable") -> Availability {
        switch state ?? fallback {
        case "in_library", "album_in_library": .inLibrary(albumID: navidromeAlbumId ?? fallbackAlbumID)
        case "partial": .partial(have: have ?? 0, expected: expected ?? 0, albumID: navidromeAlbumId ?? fallbackAlbumID,
                                 requestable: requestable ?? false)
        case "requestable": .requestable
        case "on_server": storeAlbumId.map { .onServer(storeAlbumID: $0) } ?? .requestable
        case "catalog": .catalogOnly
        // requested, searching, downloading, importing, scanning, needs_attention, and any state a
        // newer server adds: something is under way, so never offer to request it again.
        case let other: .requested(requestID: requestId, state: other, stillLooking: stillLooking ?? false)
        }
    }
}

private func ownArtwork(_ url: String?, _ host: String?) -> URL? {
    guard let url, let host, let parsed = URL(string: url), parsed.host?.lowercased() == host else { return nil }
    return parsed
}

private struct TracklistDTO: Decodable {
    let position: Int?
    let title: String?
    let durationMs: Int?
    let discNumber: Int?
    let musicBrainzRecordingId: String?

    var model: CatalogTracklistEntry {
        CatalogTracklistEntry(position: position ?? 0, disc: discNumber ?? 1, title: title ?? "", durationMs: durationMs,
                              recordingID: musicBrainzRecordingId)
    }
}

private struct HitDTO: Decodable {
    let kind: String
    let key: String
    let title: String?
    let name: String?
    let artist: String?
    let year: Int?
    let albumType: String?
    let durationMs: Int?
    let artwork: ArtworkDTO?
    let ids: IdsDTO?
    let album: AlbumRefDTO?
    let availability: AvailabilityDTO?
    let tracks: [TracklistDTO]?

    func artist(_ host: String?, defaultState: String = "requestable") -> CatalogArtist {
        CatalogArtist(key: key, musicBrainzID: ids?.musicBrainzArtistId, name: name ?? title ?? "",
                      artworkURL: ownArtwork(artwork?.url, host),
                      availability: (availability ?? AvailabilityDTO.unknown).model(fallbackAlbumID: nil, default: defaultState))
    }

    func album(_ host: String?) -> CatalogAlbum {
        CatalogAlbum(key: key, releaseGroupID: ids?.musicBrainzReleaseGroupId, title: title ?? name ?? "", artist: artist,
                     year: year, albumType: albumType, artworkURL: ownArtwork(artwork?.url, host),
                     availability: (availability ?? .unknown).model(fallbackAlbumID: ids?.navidromeAlbumId))
    }

    func track(_ host: String?) -> CatalogTrack {
        let recording = key.hasPrefix("mb:rec:") ? String(key.dropFirst("mb:rec:".count)) : nil
        return CatalogTrack(key: key, title: title ?? "", artist: artist, durationMs: durationMs, albumTitle: album?.title,
                            albumReleaseGroupID: album?.musicBrainzReleaseGroupId,
                            availability: (availability ?? .unknown).model(fallbackAlbumID: album?.navidromeAlbumId),
                            recordingID: recording?.isEmpty == false ? recording : nil,
                            artworkURL: ownArtwork(artwork?.url, host))
    }
}

private extension AvailabilityDTO {
    static let unknown = AvailabilityDTO(state: nil, navidromeAlbumId: nil, requestId: nil, stillLooking: nil, have: nil,
                                      expected: nil, storeAlbumId: nil, requestable: nil)
}

private struct SearchDTO: Decodable {
    struct Sources: Decodable { let catalog: String? }
    let sources: Sources?
    let results: [HitDTO]?
    let partial: Bool?
    let ranked: Bool?
    let best: String?
    let popularity: [String: Float]?

    func model(_ host: String?) -> CatalogResults {
        let hits = results ?? []
        var r = CatalogResults()
        r.artists = hits.filter { $0.kind == "artist" }.map { $0.artist(host) }
        r.albums = hits.filter { $0.kind == "album" }.map { $0.album(host) }
        r.tracks = hits.filter { $0.kind == "track" }.map { $0.track(host) }
        r.catalogAvailable = sources?.catalog != "unavailable"
        r.partial = partial ?? false
        r.ranked = ranked ?? false
        r.bestKey = r.ranked ? best : nil
        r.popularity = popularity ?? [:]
        return r
    }
}

private struct BiographyDTO: Decodable {
    let text: String?
    let url: String?
}

private struct CatalogArtistDTO: Decodable {
    let key: String
    let name: String?
    let disambiguation: String?
    let artwork: ArtworkDTO?
    let biography: BiographyDTO?
    let ids: IdsDTO?
    let availability: AvailabilityDTO?
    let topTracks: [HitDTO]?
    let similarArtists: [HitDTO]?

    func model(_ host: String?) -> CatalogArtistPage {
        CatalogArtistPage(
            artist: CatalogArtist(key: key, musicBrainzID: ids?.musicBrainzArtistId, name: name ?? "",
                                  artworkURL: ownArtwork(artwork?.url, host),
                                  availability: (availability ?? .unknown).model(fallbackAlbumID: nil, default: "catalog")),
            disambiguation: disambiguation?.isEmpty == false ? disambiguation : nil,
            biography: biography?.text?.isEmpty == false ? biography?.text : nil,
            biographyURL: biography?.url.flatMap(URL.init(string:)),
            topTracks: (topTracks ?? []).filter { $0.kind == "track" }.map { $0.track(host) },
            similarArtists: (similarArtists ?? []).filter { $0.kind == "artist" }.map { $0.artist(host, defaultState: "catalog") })
    }
}

private struct DiscoverDTO: Decodable {
    struct Playlist: Decodable { let id: String; let title: String?; let description: String? }
    let newReleases: [HitDTO]?
    let forYou: [HitDTO]?
    let artists: [HitDTO]?
    let albums: [HitDTO]?
    let playlists: [Playlist]?

    func model(_ host: String?) -> Discovery {
        var d = Discovery()
        d.newReleases = (newReleases ?? []).filter { $0.kind == "album" }.map { $0.album(host) }
        d.forYou = (forYou ?? []).filter { $0.kind == "track" }.map { $0.track(host) }
        d.artists = (artists ?? []).filter { $0.kind == "artist" }.map { $0.artist(host) }
        d.albums = (albums ?? []).filter { $0.kind == "album" }.map { $0.album(host) }
        d.playlists = (playlists ?? []).filter { !($0.title ?? "").isEmpty }
            .map { DiscoveryPlaylist(id: $0.id, title: $0.title ?? "", description: $0.description) }
        return d
    }
}

private struct DiscoveryPlaylistPageDTO: Decodable {
    let id: String
    let title: String?
    let description: String?
    let tracks: [HitDTO]?
}

private struct RequestBody: Encodable {
    let kind: String
    let musicBrainzReleaseGroupId: String
    let musicBrainzRecordingId: String?
}

private struct CreatedDTO: Decodable { let id: Int }

private struct ConflictDTO: Decodable {
    struct Inner: Decodable { let code: String?; let requestId: Int?; let navidromeAlbumId: String? }
    let error: Inner?
}

private struct ActionResultDTO: Decodable { let message: String? }

private struct RequestListDTO: Decodable {
    let items: [RequestDTO]
}

private struct RequestDTO: Decodable {
    struct Ids: Decodable { let navidromeAlbumId: String? }
    struct Pipeline: Decodable {
        struct Attention: Decodable { let reason: String? }
        let stages: [String]?
        let stageIndex: Int?
        let percent: Float?
        let detail: String?
        let attention: Attention?
    }
    struct Failure: Decodable { let message: String? }
    struct Play: Decodable { let navidromeAlbumId: String? }

    let id: Int
    let title: String?
    let artist: String?
    let year: Int?
    let artwork: ArtworkDTO?
    let ids: Ids?
    let status: String?
    let stillLooking: Bool?
    let pipeline: Pipeline?
    let error: Failure?
    let createdAt: String?
    let play: Play?

    func model(_ host: String?) -> MusicRequest {
        MusicRequest(id: id, title: title ?? "", artist: artist, year: year, artworkURL: ownArtwork(artwork?.url, host),
                     status: status ?? "pending", stillLooking: stillLooking ?? false, stages: pipeline?.stages ?? [],
                     stageIndex: pipeline?.stageIndex ?? 0, percent: pipeline?.percent ?? 0, detail: pipeline?.detail ?? "",
                     attention: pipeline?.attention?.reason, error: error?.message,
                     albumID: play?.navidromeAlbumId ?? ids?.navidromeAlbumId, createdAt: createdAt)
    }
}

private struct PlayNowBody: Encodable {
    let musicBrainzRecordingId: String
    let musicBrainzReleaseGroupId: String?
    let title: String
    let artist: String
    let durationMs: Int?
    let queued: Bool
}

private struct PlayNowDTO: Decodable {
    let id: String
    let status: String?
    let playable: Bool?
    let percent: Double?
    let title: String?
    let artist: String?
    let durationMs: Int?
    let format: String?
    let error: String?

    var model: PlayNowStream {
        PlayNowStream(id: id, status: status ?? "searching", playable: playable ?? false, percent: percent ?? 0,
                      title: title ?? "", artist: artist, durationMs: durationMs, format: format, error: error)
    }
}

private struct LyricsDTO: Decodable {
    struct Line: Decodable { let startMs: Int?; let text: String? }
    let synced: Bool?
    let lines: [Line]?
    let instrumental: Bool?
    let source: String?

    var model: Lyrics {
        let synced = (synced ?? false) && !(lines ?? []).isEmpty
        return Lyrics(lines: (lines ?? []).map { LyricLine(startMs: synced ? $0.startMs : nil, text: $0.text ?? "") },
                      synced: synced, instrumental: instrumental ?? false,
                      source: source == "lrclib" ? "LRCLIB" : source)
    }
}

private struct DeviceDTO: Decodable {
    let id: String
    let name: String?
    let platform: String?
    let lastSeenAt: String?
    let current: Bool?
}

private struct RemovedDTO: Decodable {
    let id: Int
    let title: String?
    let artist: String?
    let removedAt: String?
    let restorableUntil: String?
}

private struct TokenBody: Encodable { let token: String }

private struct LastFmStartDTO: Decodable {
    let token: String
    let url: String
}

private struct ScrobbleLinksDTO: Decodable {
    struct Link: Decodable {
        let available: Bool?
        let connected: Bool?
        let account: String?
        var model: ScrobbleLinks.Link {
            ScrobbleLinks.Link(available: available ?? false, connected: connected ?? false,
                               account: account?.isEmpty == false ? account : nil)
        }
    }
    let listenbrainz: Link?
    let lastfm: Link?

    var model: ScrobbleLinks {
        ScrobbleLinks(listenBrainz: listenbrainz?.model ?? .init(available: false, connected: false, account: nil),
                      lastFm: lastfm?.model ?? .init(available: false, connected: false, account: nil))
    }
}

private struct SinglesDTO: Decodable {
    struct Album: Decodable { let navidromeAlbumId: String; let have: Int?; let expected: Int? }
    let albums: [Album]
}
