import Foundation

/// What signing in to a Jellyfin server leaves: a token for this device.
public struct JellyfinSession: Sendable, Codable, Equatable {
    public var userID: String
    public var accessToken: String
    public var deviceID: String

    public init(userID: String, accessToken: String, deviceID: String) {
        self.userID = userID
        self.accessToken = accessToken
        self.deviceID = deviceID
    }
}

public enum JellyfinError: Error, Sendable, Equatable, CustomStringConvertible {
    case http(status: Int)
    case decoding(String)

    public var description: String {
        switch self {
        case let .http(status): "The Jellyfin server answered HTTP \(status)"
        case let .decoding(detail): "Unexpected answer from the Jellyfin server: \(detail)"
        }
    }
}

/// The Jellyfin API, as the Android app's JellyfinMediaProvider uses it. Jellyfin has likes and
/// favourites rather than star ratings: 3 stars and up is a like, under 3 a dislike, 0 clears it.
public struct JellyfinClient: MusicLibrary {
    public let baseURL: URL
    public let session: JellyfinSession
    private let transport: HTTPTransport
    private static let ticksPerSecond = 10_000_000.0
    private static let pageSize = 500

    private static let trackFields = "MediaSources,Genres,DateCreated,Path,ProviderIds,ParentId,SortName"
    private static let albumFields = "SortName,DateCreated,Genres,ProviderIds,ChildCount,ItemCounts,CumulativeRunTimeTicks"
    private static let artistFields = "SortName,ProviderIds,ItemCounts,ChildCount,Overview"
    private static let playlistFields = "ChildCount,DateCreated,Overview,CumulativeRunTimeTicks,DateLastMediaAdded"

    public init(baseURL: URL, session: JellyfinSession, transport: HTTPTransport = URLSessionTransport()) {
        self.baseURL = baseURL
        self.session = session
        self.transport = transport
    }

    // MARK: Signing in

    private struct PublicInfo: Decodable {
        let ProductName: String?
        let ServerName: String?
        let Id: String?
    }

    /// Whether a Jellyfin server answers at this address; its login isn't needed to ask.
    public static func isJellyfin(at baseURL: URL, transport: HTTPTransport) async throws -> Bool {
        let response = try await transport.send(HTTPRequest(url: baseURL.appendingPathComponent("System/Info/Public")))
        guard (200..<300).contains(response.status),
              let info = try? JSONDecoder().decode(PublicInfo.self, from: response.body) else { return false }
        return info.ProductName?.localizedCaseInsensitiveContains("jellyfin") == true
    }

    private struct AuthResult: Decodable {
        struct Account: Decodable {
            let Id: String
            let Name: String?
        }
        let AccessToken: String?
        let User: Account?
    }

    /// Signs in with a new device id. Throws [ConnectError.wrongPassword] when the server says no.
    public static func signIn(baseURL: URL, username: String, password: String, deviceName: String,
                              transport: HTTPTransport) async throws -> (session: JellyfinSession, username: String) {
        struct Body: Encodable {
            let Username: String
            let Pw: String
        }
        let deviceID = UUID().uuidString
        let response = try await transport.send(HTTPRequest(
            method: "POST", url: baseURL.appendingPathComponent("Users/AuthenticateByName"),
            headers: ["Authorization": header(deviceName: deviceName, deviceID: deviceID, token: nil), "Content-Type": "application/json"],
            body: try JSONEncoder().encode(Body(Username: username, Pw: password))))
        if response.status == 401 || response.status == 403 { throw ConnectError.wrongPassword }
        guard (200..<300).contains(response.status) else { throw JellyfinError.http(status: response.status) }
        guard let auth = try? JSONDecoder().decode(AuthResult.self, from: response.body),
              let token = auth.AccessToken, !token.isEmpty, let user = auth.User else {
            throw JellyfinError.decoding("no access token")
        }
        return (JellyfinSession(userID: user.Id, accessToken: token, deviceID: deviceID), user.Name ?? username)
    }

    private static func header(deviceName: String, deviceID: String, token: String?) -> String {
        var h = "MediaBrowser Client=\"Polyphony\", Device=\"\(deviceName)\", DeviceId=\"\(deviceID)\", Version=\"1.0\""
        if let token { h += ", Token=\"\(token)\"" }
        return h
    }

    // MARK: The API

    private enum Query {
        static func items(_ pairs: [(String, String?)]) -> [URLQueryItem] {
            pairs.compactMap { p in p.1.map { URLQueryItem(name: p.0, value: $0) } }
        }
    }

    private func url(_ path: String, _ query: [(String, String?)] = []) -> URL {
        var parts = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        let items = Query.items(query)
        if !items.isEmpty { parts.queryItems = items }
        return parts.url!
    }

    private var authorization: String {
        Self.header(deviceName: "Mac", deviceID: session.deviceID, token: session.accessToken)
    }

    @discardableResult
    private func send(_ method: String, _ path: String, _ query: [(String, String?)] = [], body: Data? = nil) async throws -> HTTPResponse {
        var headers = ["Authorization": authorization]
        if body != nil { headers["Content-Type"] = "application/json" }
        let response = try await transport.send(HTTPRequest(method: method, url: url(path, query), headers: headers, body: body))
        guard (200..<300).contains(response.status) else { throw JellyfinError.http(status: response.status) }
        return response
    }

    private func get<T: Decodable>(_ path: String, _ query: [(String, String?)] = []) async throws -> T {
        let response = try await send("GET", path, query)
        do {
            return try JSONDecoder().decode(T.self, from: response.body)
        } catch {
            throw JellyfinError.decoding("\(path): \(error)")
        }
    }

    private struct Page: Decodable { let Items: [ItemDTO]? }

    private func items(_ query: [(String, String?)]) async throws -> [ItemDTO] {
        let page: Page = try await get("Items", [("userId", session.userID), ("recursive", "true"), ("enableUserData", "true"),
                                                 ("enableTotalRecordCount", "false")] + query)
        return page.Items ?? []
    }

    /// Every page of a list: Jellyfin's are capped only by what's asked for.
    private func allItems(_ query: [(String, String?)]) async throws -> [ItemDTO] {
        var out: [ItemDTO] = []
        while true {
            let page = try await items(query + [("startIndex", "\(out.count)"), ("limit", "\(Self.pageSize)")])
            out += page
            if page.count < Self.pageSize { return out }
        }
    }

    public func ping() async throws {
        try await send("GET", "System/Info/Public")
    }

    public func artists() async throws -> [Artist] {
        // /Artists/AlbumArtists takes the same sort and fields; a server without it has /Items.
        let query: [(String, String?)] = [("userId", session.userID), ("fields", Self.artistFields), ("sortBy", "SortName"), ("sortOrder", "Ascending")]
        do {
            let page: Page = try await get("Artists/AlbumArtists", query)
            return (page.Items ?? []).map(\.artist)
        } catch JellyfinError.http(status: 404) {
            return try await allItems(query + [("includeItemTypes", "MusicArtist")]).map(\.artist)
        }
    }

    public func artist(id: String) async throws -> (Artist, [Album]) {
        async let item: ItemDTO = get("Items/\(id)", [("userId", session.userID), ("fields", Self.artistFields)])
        async let albums = allItems([("albumArtistIds", id), ("includeItemTypes", "MusicAlbum"), ("fields", Self.albumFields),
                                     ("sortBy", "ProductionYear,SortName"), ("sortOrder", "Descending,Ascending")])
        return (try await item.artist, try await albums.map(\.album))
    }

    public func album(id: String) async throws -> Album {
        async let item: ItemDTO = get("Items/\(id)", [("userId", session.userID), ("fields", Self.albumFields)])
        async let tracks = allItems([("parentId", id), ("includeItemTypes", "Audio"), ("fields", Self.trackFields),
                                     ("sortBy", "ParentIndexNumber,IndexNumber,SortName"), ("sortOrder", "Ascending")])
        var album = try await item.album
        album.tracks = try await tracks.map(\.track)
        if album.songCount == 0 { album.songCount = album.tracks.count }
        return album
    }

    public func albums(_ type: AlbumListType, size: Int, offset: Int) async throws -> [Album] {
        let sort: (String, String)
        var filters: String?
        switch type {
        case .newest: sort = ("DateCreated,SortName", "Descending,Ascending")
        case .recent:
            sort = ("DatePlayed,SortName", "Descending,Ascending")
            filters = "IsPlayed"
        case .frequent:
            sort = ("PlayCount,SortName", "Descending,Ascending")
            filters = "IsPlayed"
        case .random: sort = ("Random", "Ascending")
        case .starred:
            sort = ("SortName", "Ascending")
            filters = "IsFavorite"
        case .highest: sort = ("CommunityRating,SortName", "Descending,Ascending")
        case .alphabeticalByName: sort = ("SortName", "Ascending")
        case .alphabeticalByArtist: sort = ("AlbumArtist,SortName", "Ascending")
        }
        return try await items([("includeItemTypes", "MusicAlbum"), ("startIndex", "\(offset)"), ("limit", "\(size)"), ("sortBy", sort.0),
                                ("sortOrder", sort.1), ("filters", filters), ("fields", Self.albumFields)]).map(\.album)
    }

    public func search(_ query: String, artists: Int, albums: Int, tracks: Int, trackOffset: Int) async throws -> SearchResults {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        // Every song, a page at a time: the Songs list.
        if term.isEmpty {
            let page = try await items([("includeItemTypes", "Audio"), ("startIndex", "\(trackOffset)"), ("limit", "\(tracks)"),
                                        ("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName"), ("sortOrder", "Ascending"),
                                        ("fields", Self.trackFields)])
            return SearchResults(tracks: page.map(\.track))
        }
        func search(_ kind: String, _ limit: Int, _ fields: String) async throws -> [ItemDTO] {
            guard limit > 0 else { return [] }
            return try await items([("searchTerm", term), ("includeItemTypes", kind), ("limit", "\(limit)"), ("startIndex", "\(trackOffset)"), ("fields", fields)])
        }
        async let foundArtists = search("MusicArtist", artists, Self.artistFields)
        async let foundAlbums = search("MusicAlbum", albums, Self.albumFields)
        async let foundTracks = search("Audio", tracks, Self.trackFields)
        return SearchResults(artists: try await foundArtists.map(\.artist), albums: try await foundAlbums.map(\.album),
                             tracks: try await foundTracks.map(\.track))
    }

    public func songs(_ type: SongListType, size: Int) async throws -> [Track] {
        switch type {
        case .random:
            return try await items([("includeItemTypes", "Audio"), ("sortBy", "Random"), ("limit", "\(size)"), ("fields", Self.trackFields)]).map(\.track)
        case let .genre(name):
            return try await items([("includeItemTypes", "Audio"), ("genres", name), ("limit", "\(size)"),
                                    ("sortBy", "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName"), ("sortOrder", "Ascending"),
                                    ("fields", Self.trackFields)]).map(\.track)
        }
    }

    public func albums(genre: String, size: Int, offset: Int) async throws -> [Album] {
        try await items([("includeItemTypes", "MusicAlbum"), ("genres", genre), ("startIndex", "\(offset)"), ("limit", "\(size)"),
                         ("sortBy", "SortName"), ("sortOrder", "Ascending"), ("fields", Self.albumFields)]).map(\.album)
    }

    public func genres() async throws -> [Genre] {
        let page: Page = try await get("Genres", [("userId", session.userID), ("includeItemTypes", "Audio,MusicAlbum"), ("fields", "ItemCounts"),
                                                  ("sortBy", "SortName"), ("sortOrder", "Ascending")])
        return (page.Items ?? []).compactMap { dto in
            guard let name = dto.Name, !name.isEmpty else { return nil }
            return Genre(name: name, songCount: dto.SongCount ?? 0, albumCount: dto.AlbumCount ?? 0)
        }
    }

    public func artistInfo(id: String) async throws -> ArtistInfo {
        async let item: ItemDTO = get("Items/\(id)", [("userId", session.userID), ("fields", Self.artistFields)])
        // Not every server knows similar artists: the page still shows the rest.
        async let similar: Page? = try? get("Artists/\(id)/Similar", [("userId", session.userID), ("limit", "12"), ("fields", Self.artistFields)])
        let dto = try await item
        return ArtistInfo(biography: dto.Overview.flatMap { $0.isEmpty ? nil : $0 },
                          imageURL: dto.hasOwnImage ? coverArtURL(id: id, size: 600) : nil,
                          similarArtists: (await similar?.Items ?? []).map(\.artist))
    }

    public func starred() async throws -> Starred {
        func favourites(_ kind: String, _ fields: String) async throws -> [ItemDTO] {
            try await allItems([("includeItemTypes", kind), ("filters", "IsFavorite"), ("sortBy", "SortName"), ("sortOrder", "Ascending"), ("fields", fields)])
        }
        async let artists = favourites("MusicArtist", Self.artistFields)
        async let albums = favourites("MusicAlbum", Self.albumFields)
        async let tracks = favourites("Audio", Self.trackFields)
        return Starred(artists: try await artists.map(\.artist), albums: try await albums.map(\.album), tracks: try await tracks.map(\.track))
    }

    public func setStarred(_ item: LibraryItem, _ starred: Bool) async throws {
        let id = switch item {
        case let .track(id), let .album(id), let .artist(id): id
        }
        try await send(starred ? "POST" : "DELETE", "UserFavoriteItems/\(id)", [("userId", session.userID)])
    }

    public func setRating(id: String, _ rating: Int) async throws {
        if rating <= 0 {
            try await send("DELETE", "UserItems/\(id)/Rating", [("userId", session.userID)])
        } else {
            try await send("POST", "UserItems/\(id)/Rating", [("userId", session.userID), ("likes", rating >= 3 ? "true" : "false")])
        }
    }

    public func scrobble(trackID: String, at time: Date, submission: Bool) async throws {
        if submission {
            let date = ISO8601DateFormatter().string(from: time)
            try await send("POST", "UserPlayedItems/\(trackID)", [("userId", session.userID), ("datePlayed", date)])
        } else {
            struct Body: Encodable { let ItemId: String }
            try await send("POST", "Sessions/Playing", body: try JSONEncoder().encode(Body(ItemId: trackID)))
        }
    }

    // MARK: Playlists

    public func playlists() async throws -> [Playlist] {
        try await allItems([("includeItemTypes", "Playlist"), ("sortBy", "SortName"), ("sortOrder", "Ascending"), ("fields", Self.playlistFields)]).map(\.playlist)
    }

    private struct PlaylistInfo: Decodable { let OpenAccess: Bool? }

    public func playlist(id: String) async throws -> Playlist {
        async let item: ItemDTO = get("Items/\(id)", [("userId", session.userID), ("fields", Self.playlistFields)])
        async let info: PlaylistInfo? = try? get("Playlists/\(id)")
        async let entries = playlistEntries(id)
        var playlist = try await item.playlist
        playlist.isPublic = await info?.OpenAccess ?? false
        playlist.tracks = try await entries.map(\.track)
        playlist.songCount = playlist.tracks.count
        return playlist
    }

    private func playlistEntries(_ id: String) async throws -> [ItemDTO] {
        let page: Page = try await get("Playlists/\(id)/Items", [("userId", session.userID), ("fields", Self.trackFields)])
        return page.Items ?? []
    }

    public func createPlaylist(name: String, trackIDs: [String]) async throws -> Playlist? {
        struct Body: Encodable {
            let Name: String
            let Ids: [String]
            let UserId: String
            let MediaType: String
        }
        struct Created: Decodable { let Id: String }
        let response = try await send("POST", "Playlists", body: try JSONEncoder().encode(Body(Name: name, Ids: trackIDs, UserId: session.userID, MediaType: "Audio")))
        guard let created = try? JSONDecoder().decode(Created.self, from: response.body) else { return nil }
        return try await playlist(id: created.Id)
    }

    public func change(playlist id: String, _ change: PlaylistChange) async throws {
        if change.name != nil || change.isPublic != nil {
            struct Body: Encodable {
                let Name: String?
                let IsPublic: Bool?
            }
            try await send("POST", "Playlists/\(id)", body: try JSONEncoder().encode(Body(Name: change.name, IsPublic: change.isPublic)))
        }
        if !change.removingIndices.isEmpty {
            // Jellyfin removes by entry, not by place.
            let entries = try await playlistEntries(id)
            let ids = change.removingIndices.compactMap { entries.indices.contains($0) ? entries[$0].PlaylistItemId : nil }
            if !ids.isEmpty { try await send("DELETE", "Playlists/\(id)/Items", [("entryIds", ids.joined(separator: ","))]) }
        }
        if !change.adding.isEmpty {
            try await send("POST", "Playlists/\(id)/Items", [("ids", change.adding.joined(separator: ",")), ("userId", session.userID)])
        }
    }

    public func deletePlaylist(id: String) async throws {
        try await send("DELETE", "Items/\(id)")
    }

    // MARK: Lyrics

    private struct LyricsDTO: Decodable {
        struct Meta: Decodable { let IsSynced: Bool? }
        struct Line: Decodable {
            let Text: String?
            let Start: Int64?
        }
        let Metadata: Meta?
        let Lyrics: [Line]?
    }

    public func lyrics(trackID: String) async throws -> Lyrics? {
        let dto: LyricsDTO
        do {
            dto = try await get("Audio/\(trackID)/Lyrics")
        } catch JellyfinError.http(status: 404) {
            return nil
        }
        let lines = (dto.Lyrics ?? []).map { LyricLine(startMs: $0.Start.map { Int($0 / 10_000) }, text: $0.Text ?? "") }
        guard !lines.isEmpty else { return nil }
        return PolyphonyKit.Lyrics(lines: lines, synced: dto.Metadata?.IsSynced ?? lines.contains { $0.startMs != nil }, instrumental: false, source: "Jellyfin")
    }

    // MARK: URLs the player and image views load themselves

    private static let losslessContainers = ["flac", "m4a|alac", "wav", "aiff"]
    /// What Apple's player, and speakers, play as they are. No Ogg: AVFoundation can't.
    private static let lossyContainers = ["mp3", "aac", "m4a|aac", "m4b|aac"]

    /// Jellyfin's universal endpoint direct-plays a file in one of `container` within the caps, and
    /// transcodes the rest. A lossy profile never direct-plays a lossless original.
    public func streamURL(trackID: String, choice: StreamChoice) -> URL {
        let target: (codec: String, container: String)
        switch choice.format {
        case "flac": target = ("flac", "flac")
        case "aac": target = ("aac", "aac")
        case "opus": target = ("opus", "opus")
        case "ogg": target = ("vorbis", "ogg")
        default: target = ("mp3", "mp3")
        }
        let lossy = choice.format != nil && choice.format != "flac"
        let bitrate = choice.maxBitRateKbps.map { "\($0 * 1000)" }
        return url("Audio/\(trackID)/universal", [
            ("container", (lossy ? Self.lossyContainers : Self.losslessContainers + Self.lossyContainers).joined(separator: ",")),
            ("deviceId", session.deviceID), ("userId", session.userID), ("audioCodec", target.codec),
            ("maxStreamingBitrate", bitrate), ("audioBitRate", lossy ? bitrate : nil),
            ("transcodingContainer", target.container), ("transcodingProtocol", "http"),
            ("maxAudioSampleRate", choice.maxSampleRateHz.map(String.init)), ("maxAudioBitDepth", choice.maxBitDepth.map(String.init)),
            ("enableRedirection", "true"), ("ApiKey", session.accessToken), ("api_key", session.accessToken),
        ])
    }

    public func downloadURL(trackID: String) -> URL {
        url("Items/\(trackID)/Download", [("ApiKey", session.accessToken), ("api_key", session.accessToken)])
    }

    public func coverArtURL(id: String, size: Int?) -> URL {
        url("Items/\(id)/Images/Primary", [("maxWidth", size.map(String.init)), ("maxHeight", size.map(String.init)), ("quality", "90")])
    }
}

/// An item as Jellyfin sends it: one shape for artists, albums, songs, playlists and genres.
private struct ItemDTO: Decodable {
    struct NameID: Decodable {
        let Name: String?
        let Id: String?
    }

    struct UserInfo: Decodable {
        let IsFavorite: Bool?
        let Likes: Bool?
        let PlayCount: Int?
    }

    struct Stream: Decodable {
        let kind: String?
        let BitRate: Int?
        let SampleRate: Int?
        let BitDepth: Int?
        let Channels: Int?

        enum CodingKeys: String, CodingKey {
            case kind = "Type"
            case BitRate, SampleRate, BitDepth, Channels
        }
    }

    struct Source: Decodable {
        let Container: String?
        let Bitrate: Int?
        let Size: Int64?
        let RunTimeTicks: Int64?
        let MediaStreams: [Stream]?
    }

    let Id: String
    let Name: String?
    let Overview: String?
    let AlbumCount: Int?
    let SongCount: Int?
    let ChildCount: Int?
    let ProductionYear: Int?
    let Genres: [String]?
    let RunTimeTicks: Int64?
    let CumulativeRunTimeTicks: Int64?
    let ImageTags: [String: String]?
    let UserData: UserInfo?
    let AlbumArtist: String?
    let AlbumArtists: [NameID]?
    let ArtistItems: [NameID]?
    let Artists: [String]?
    let Album: String?
    let AlbumId: String?
    let AlbumPrimaryImageTag: String?
    let ParentPrimaryImageItemId: String?
    let IndexNumber: Int?
    let ParentIndexNumber: Int?
    let Container: String?
    let MediaSources: [Source]?
    let NormalizationGain: Double?
    let AlbumNormalizationGain: Double?
    let Path: String?
    let PlaylistItemId: String?

    var hasOwnImage: Bool { ImageTags?["Primary"] != nil }

    private var likesAsRating: Int? {
        switch UserData?.Likes {
        case true?: 5
        case false?: 1
        case nil: nil
        }
    }

    private func seconds(_ ticks: Int64?) -> TimeInterval { TimeInterval(ticks ?? 0) / 10_000_000 }

    var artist: Artist {
        PolyphonyKit.Artist(id: Id, name: Name ?? "Unknown artist", albumCount: AlbumCount ?? ChildCount ?? 0,
                            coverArtID: hasOwnImage ? Id : nil, starred: UserData?.IsFavorite == true)
    }

    var album: Album {
        PolyphonyKit.Album(id: Id, name: Name ?? "Unknown album",
                           artistName: AlbumArtist ?? AlbumArtists?.first?.Name ?? Artists?.first,
                           artistID: AlbumArtists?.first?.Id ?? ArtistItems?.first?.Id,
                           year: ProductionYear, genre: Genres?.first, songCount: ChildCount ?? SongCount ?? 0,
                           duration: seconds(RunTimeTicks ?? CumulativeRunTimeTicks), coverArtID: hasOwnImage ? Id : nil,
                           starred: UserData?.IsFavorite == true, rating: likesAsRating)
    }

    var track: Track {
        let source = MediaSources?.first
        let audio = source?.MediaStreams?.first { $0.kind == "Audio" }
        let container = (source?.Container ?? Container)?.lowercased().split(separator: ",").first.map(String.init)
        // Songs usually have no image of their own: the album's.
        let cover = hasOwnImage ? Id : AlbumPrimaryImageTag != nil ? AlbumId : ParentPrimaryImageItemId ?? AlbumId
        let joined = Artists?.filter { !$0.isEmpty }.joined(separator: ", ")
        let gain: ReplayGain.Tags? = NormalizationGain == nil && AlbumNormalizationGain == nil ? nil
            : ReplayGain.Tags(trackGainDb: NormalizationGain, albumGainDb: AlbumNormalizationGain)
        let bitrate = (source?.Bitrate ?? audio?.BitRate).map { $0 / 1000 }
        return PolyphonyKit.Track(id: Id, title: Name ?? "Unknown title", albumID: AlbumId, albumName: Album,
                                  artistName: joined?.isEmpty == false ? joined : AlbumArtist,
                                  artistID: ArtistItems?.first?.Id ?? AlbumArtists?.first?.Id,
                                  trackNumber: IndexNumber, discNumber: ParentIndexNumber,
                                  duration: seconds(RunTimeTicks ?? source?.RunTimeTicks),
                                  bitRateKbps: bitrate.flatMap { $0 > 0 ? $0 : nil }, bitDepth: audio?.BitDepth, sampleRateHz: audio?.SampleRate,
                                  container: container, sizeBytes: source?.Size, coverArtID: cover,
                                  starred: UserData?.IsFavorite == true, rating: likesAsRating, replayGain: gain,
                                  details: .init(year: ProductionYear, genre: Genres?.first, playCount: UserData?.PlayCount, path: Path, channels: audio?.Channels))
    }

    var playlist: Playlist {
        PolyphonyKit.Playlist(id: Id, name: Name ?? "Playlist", comment: Overview.flatMap { $0.isEmpty ? nil : $0 },
                              songCount: ChildCount ?? SongCount ?? 0, duration: seconds(CumulativeRunTimeTicks ?? RunTimeTicks),
                              coverArtID: hasOwnImage ? Id : nil)
    }
}
