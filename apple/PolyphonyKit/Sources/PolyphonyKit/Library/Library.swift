import Foundation

// The library as the app sees it, whichever server it came from. Ids are the server's own; the
// cover art id is turned into a URL by the client that knows the server (SubsonicClient.coverArtURL).

public struct Artist: Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    public var albumCount: Int
    public var coverArtID: String?
    public var starred: Bool

    public init(id: String, name: String, albumCount: Int = 0, coverArtID: String? = nil, starred: Bool = false) {
        self.id = id
        self.name = name
        self.albumCount = albumCount
        self.coverArtID = coverArtID
        self.starred = starred
    }
}

public struct Album: Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    public var artistName: String?
    public var artistID: String?
    public var year: Int?
    public var genre: String?
    public var songCount: Int
    public var duration: TimeInterval
    public var coverArtID: String?
    public var starred: Bool
    /// Filled in by `SubsonicClient.album(id:)`; empty in lists.
    public var tracks: [Track]

    public init(id: String, name: String, artistName: String? = nil, artistID: String? = nil, year: Int? = nil,
                genre: String? = nil, songCount: Int = 0, duration: TimeInterval = 0, coverArtID: String? = nil,
                starred: Bool = false, tracks: [Track] = []) {
        self.id = id
        self.name = name
        self.artistName = artistName
        self.artistID = artistID
        self.year = year
        self.genre = genre
        self.songCount = songCount
        self.duration = duration
        self.coverArtID = coverArtID
        self.starred = starred
        self.tracks = tracks
    }
}

public struct Track: Sendable, Hashable, Identifiable, Codable {
    public let id: String
    public var title: String
    public var albumID: String?
    public var albumName: String?
    public var artistName: String?
    public var artistID: String?
    public var trackNumber: Int?
    public var discNumber: Int?
    public var duration: TimeInterval
    public var bitRateKbps: Int?
    public var bitDepth: Int?
    public var sampleRateHz: Int?
    /// The file's format as the server reports it ("flac", "mp3").
    public var container: String?
    public var sizeBytes: Int64?
    public var coverArtID: String?
    public var starred: Bool
    /// The user's 1–5 stars; nil when unrated.
    public var rating: Int?

    public init(id: String, title: String, albumID: String? = nil, albumName: String? = nil, artistName: String? = nil,
                artistID: String? = nil, trackNumber: Int? = nil, discNumber: Int? = nil, duration: TimeInterval = 0,
                bitRateKbps: Int? = nil, bitDepth: Int? = nil, sampleRateHz: Int? = nil, container: String? = nil,
                sizeBytes: Int64? = nil, coverArtID: String? = nil, starred: Bool = false, rating: Int? = nil) {
        self.id = id
        self.title = title
        self.albumID = albumID
        self.albumName = albumName
        self.artistName = artistName
        self.artistID = artistID
        self.trackNumber = trackNumber
        self.discNumber = discNumber
        self.duration = duration
        self.bitRateKbps = bitRateKbps
        self.bitDepth = bitDepth
        self.sampleRateHz = sampleRateHz
        self.container = container
        self.sizeBytes = sizeBytes
        self.coverArtID = coverArtID
        self.starred = starred
        self.rating = rating
    }

    private static let losslessContainers: Set<String> = ["flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff"]

    public var isLossless: Bool {
        guard let container else { return false }
        return Self.losslessContainers.contains(container.lowercased())
    }
}

public struct Playlist: Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    public var comment: String?
    public var owner: String?
    public var isPublic: Bool
    public var songCount: Int
    public var duration: TimeInterval
    public var coverArtID: String?
    /// Filled in by `SubsonicClient.playlist(id:)`; empty in lists.
    public var tracks: [Track]

    public init(id: String, name: String, comment: String? = nil, owner: String? = nil, isPublic: Bool = false,
                songCount: Int = 0, duration: TimeInterval = 0, coverArtID: String? = nil, tracks: [Track] = []) {
        self.id = id
        self.name = name
        self.comment = comment
        self.owner = owner
        self.isPublic = isPublic
        self.songCount = songCount
        self.duration = duration
        self.coverArtID = coverArtID
        self.tracks = tracks
    }
}

public struct Genre: Sendable, Hashable, Identifiable {
    public var id: String { name }
    public var name: String
    public var songCount: Int
    public var albumCount: Int
}

public struct ArtistInfo: Sendable, Hashable {
    public var biography: String?
    public var imageURL: URL?
    public var similarArtists: [Artist]
}

public struct Starred: Sendable, Hashable {
    public var artists: [Artist] = []
    public var albums: [Album] = []
    public var tracks: [Track] = []

    public init(artists: [Artist] = [], albums: [Album] = [], tracks: [Track] = []) {
        self.artists = artists
        self.albums = albums
        self.tracks = tracks
    }
}

public struct SearchResults: Sendable, Hashable {
    public var artists: [Artist]
    public var albums: [Album]
    public var tracks: [Track]

    public init(artists: [Artist] = [], albums: [Album] = [], tracks: [Track] = []) {
        self.artists = artists
        self.albums = albums
        self.tracks = tracks
    }
}

/// The quality badges, as Polyphony on Android shows them: "HI-RES" for lossless above CD resolution,
/// the container ("FLAC") for lossless at CD resolution, nothing for lossy.
public enum AudioQuality {
    public static let hiRes = "HI-RES"

    public static func badge(for track: Track) -> String? {
        guard track.isLossless else { return nil }
        if (track.bitDepth ?? 0) > 16 || (track.sampleRateHz ?? 0) > 48_000 { return hiRes }
        return track.container?.uppercased() ?? "LOSSLESS"
    }

    /// The album's badge: the most common one among its tracks.
    public static func albumBadge(_ tracks: [Track]) -> String? {
        let counts = Dictionary(grouping: tracks.compactMap(badge(for:)), by: { $0 }).mapValues(\.count)
        return counts.max { $0.value < $1.value || ($0.value == $1.value && $0.key > $1.key) }?.key
    }

    /// A track's own chip, only where it differs from the album's. On a HI-RES album a lossless track
    /// at CD resolution says its bit depth ("16-BIT"): "FLAC" there read as if the rest weren't FLAC.
    public static func trackChip(_ track: Track, albumBadge: String?) -> String? {
        guard let own = badge(for: track), own != albumBadge else { return nil }
        if albumBadge == hiRes, track.isLossless { return "\(track.bitDepth ?? 16)-BIT" }
        return own
    }
}
