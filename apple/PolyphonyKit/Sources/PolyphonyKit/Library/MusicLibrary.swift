import Foundation

public enum AlbumListType: String, Sendable {
    case newest, recent, frequent, random, starred
    /// The user's top-rated albums (ratings mode's "Top rated").
    case highest
    case alphabeticalByName, alphabeticalByArtist
}

public enum SongListType: Sendable, Hashable { case random, genre(String) }

public enum LibraryItem: Sendable, Hashable { case track(String), album(String), artist(String) }

public struct PlaylistChange: Sendable, Hashable {
    public var name: String?
    public var isPublic: Bool?
    public var adding: [String]
    public var removingIndices: [Int]

    public init(name: String? = nil, isPublic: Bool? = nil, adding: [String] = [], removingIndices: [Int] = []) {
        self.name = name
        self.isPublic = isPublic
        self.adding = adding
        self.removingIndices = removingIndices
    }
}

/// A server's library, whichever API it speaks: what the app asks for, as the Android app's
/// MediaProvider. Subsonic and Navidrome (directly or through continuo) and Jellyfin have one each.
public protocol MusicLibrary: Sendable {
    func ping() async throws
    func artists() async throws -> [Artist]
    /// The artist and their albums.
    func artist(id: String) async throws -> (Artist, [Album])
    /// The album with its tracks.
    func album(id: String) async throws -> Album
    func albums(_ type: AlbumListType, size: Int, offset: Int) async throws -> [Album]
    /// [query] "" lists every song, a page at a time: how the Songs list is read.
    func search(_ query: String, artists: Int, albums: Int, tracks: Int, trackOffset: Int) async throws -> SearchResults
    func songs(_ type: SongListType, size: Int) async throws -> [Track]
    func albums(genre: String, size: Int, offset: Int) async throws -> [Album]
    func genres() async throws -> [Genre]
    func artistInfo(id: String) async throws -> ArtistInfo
    func starred() async throws -> Starred
    func setStarred(_ item: LibraryItem, _ starred: Bool) async throws
    /// 0 clears the rating; 1-5 stars.
    func setRating(id: String, _ rating: Int) async throws
    /// "Now playing" ([submission] false) or a play to count.
    func scrobble(trackID: String, at time: Date, submission: Bool) async throws
    func playlists() async throws -> [Playlist]
    func playlist(id: String) async throws -> Playlist
    @discardableResult
    func createPlaylist(name: String, trackIDs: [String]) async throws -> Playlist?
    func change(playlist id: String, _ change: PlaylistChange) async throws
    func deletePlaylist(id: String) async throws
    /// The file's own lyrics; nil when the song has none or the server lacks them.
    func lyrics(trackID: String) async throws -> Lyrics?
    /// The audio, as [choice] asks.
    func streamURL(trackID: String, choice: StreamChoice) -> URL
    /// The original file, untranscoded: what a download keeps.
    func downloadURL(trackID: String) -> URL
    func coverArtURL(id: String, size: Int?) -> URL
}

// The shorter ways the app asks.
extension MusicLibrary {
    public func albums(_ type: AlbumListType) async throws -> [Album] { try await albums(type, size: 50, offset: 0) }
    public func albums(_ type: AlbumListType, size: Int) async throws -> [Album] { try await albums(type, size: size, offset: 0) }
    public func albums(genre: String) async throws -> [Album] { try await albums(genre: genre, size: 100, offset: 0) }
    public func albums(genre: String, size: Int) async throws -> [Album] { try await albums(genre: genre, size: size, offset: 0) }
    public func search(_ query: String) async throws -> SearchResults { try await search(query, artists: 10, albums: 20, tracks: 30, trackOffset: 0) }
    public func search(_ query: String, artists: Int, albums: Int, tracks: Int) async throws -> SearchResults {
        try await search(query, artists: artists, albums: albums, tracks: tracks, trackOffset: 0)
    }
    public func songs(_ type: SongListType) async throws -> [Track] { try await songs(type, size: 100) }
    public func scrobble(trackID: String, submission: Bool) async throws { try await scrobble(trackID: trackID, at: Date(), submission: submission) }
    @discardableResult
    public func createPlaylist(name: String) async throws -> Playlist? { try await createPlaylist(name: name, trackIDs: []) }
    public func updatePlaylist(id: String, name: String? = nil, isPublic: Bool? = nil, adding: [String] = [],
                               removingIndices: [Int] = []) async throws {
        try await change(playlist: id, PlaylistChange(name: name, isPublic: isPublic, adding: adding, removingIndices: removingIndices))
    }
    public func coverArtURL(id: String) -> URL { coverArtURL(id: id, size: nil) }
}

extension SubsonicClient: MusicLibrary {
    public func change(playlist id: String, _ change: PlaylistChange) async throws {
        try await updatePlaylist(id: id, name: change.name, isPublic: change.isPublic, adding: change.adding,
                                 removingIndices: change.removingIndices)
    }

    /// "raw" is the file as it is, whatever transcoding the server would otherwise apply. Subsonic
    /// has no sample rate or bit depth to ask for.
    public func streamURL(trackID: String, choice: StreamChoice) -> URL {
        streamURL(trackID: trackID, maxBitRateKbps: choice.maxBitRateKbps,
                  format: choice.format ?? (choice.maxBitRateKbps == nil ? "raw" : nil))
    }
}
