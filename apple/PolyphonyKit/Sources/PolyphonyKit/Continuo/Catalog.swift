import Foundation

// What continuo knows beyond the library: the catalogue (MusicBrainz, ranked by what people listen
// to), requests, Play now, discovery and lyrics. Shapes follow continuo's API
// (contract/continuo-openapi.json) and the Android client (shared/requests/ContinuoDtos.kt).

/// Where a catalogue album or song stands for this server.
public enum Availability: Sendable, Hashable {
    /// In the library; [albumID] is the library album to open.
    case inLibrary(albumID: String?)
    /// Some of it is in the library.
    case partial(have: Int, expected: Int, albumID: String?, requestable: Bool)
    /// Not here, and continuo can fetch it.
    case requestable
    /// Removed from this user's library but still on the server: one tap adds it back.
    case onServer(storeAlbumID: String)
    /// Requested and on its way ([state] is continuo's: requested, searching, downloading, importing…).
    case requested(requestID: Int?, state: String, stillLooking: Bool)
    /// Known to the catalogue only (an artist, say): nothing to request directly.
    case catalogOnly

    public var isInLibrary: Bool {
        if case .inLibrary = self { return true }
        return false
    }
}

public struct CatalogArtist: Sendable, Hashable, Identifiable {
    public var id: String { key }
    public let key: String
    public var musicBrainzID: String?
    public var name: String
    public var artworkURL: URL?
    public var availability: Availability
}

public struct CatalogAlbum: Sendable, Hashable, Identifiable {
    public var id: String { key }
    public let key: String
    public var releaseGroupID: String?
    public var title: String
    public var artist: String?
    public var year: Int?
    /// "album", "single", "ep", "compilation"…
    public var albumType: String?
    public var artworkURL: URL?
    public var availability: Availability
}

public struct CatalogTrack: Sendable, Hashable, Identifiable {
    public var id: String { key }
    public let key: String
    public var title: String
    public var artist: String?
    public var durationMs: Int?
    public var albumTitle: String?
    public var albumReleaseGroupID: String?
    public var availability: Availability
    /// The MusicBrainz recording, which Play now and song requests need.
    public var recordingID: String?
    public var artworkURL: URL?

    public init(key: String, title: String, artist: String?, durationMs: Int?, albumTitle: String?,
                albumReleaseGroupID: String?, availability: Availability, recordingID: String?, artworkURL: URL?) {
        self.key = key
        self.title = title
        self.artist = artist
        self.durationMs = durationMs
        self.albumTitle = albumTitle
        self.albumReleaseGroupID = albumReleaseGroupID
        self.availability = availability
        self.recordingID = recordingID
        self.artworkURL = artworkURL
    }
}

public struct CatalogResults: Sendable, Hashable {
    public var artists: [CatalogArtist] = []
    public var albums: [CatalogAlbum] = []
    public var tracks: [CatalogTrack] = []
    /// False when continuo's catalogue couldn't be reached (the library still answered).
    public var catalogAvailable = true
    /// A quick search's first few, while typing.
    public var partial = false
    /// Ranked by listening: keep continuo's order, and lead with [bestKey] when set.
    public var ranked = false
    public var bestKey: String?
    public var popularity: [String: Float] = [:]

    public init() {}

    /// The one hit to lead with: continuo's [bestKey] when it ranked the results.
    public var best: TopHit? {
        guard ranked, let bestKey else { return nil }
        if let a = artists.first(where: { $0.key == bestKey }) { return .artist(a) }
        if let a = albums.first(where: { $0.key == bestKey }) { return .album(a) }
        if let t = tracks.first(where: { $0.key == bestKey }) { return .track(t) }
        return nil
    }

    public enum TopHit: Sendable, Hashable {
        case artist(CatalogArtist), album(CatalogAlbum), track(CatalogTrack)
    }
}

public struct CatalogTracklistEntry: Sendable, Hashable {
    public var position: Int
    public var disc: Int
    public var title: String
    public var durationMs: Int?
    public var recordingID: String?
}

public struct CatalogAlbumPage: Sendable, Hashable {
    public var album: CatalogAlbum
    public var tracks: [CatalogTracklistEntry]
}

public struct CatalogArtistPage: Sendable, Hashable {
    public var artist: CatalogArtist
    public var disambiguation: String?
    public var biography: String?
    public var biographyURL: URL?
    public var topTracks: [CatalogTrack]
    public var similarArtists: [CatalogArtist]
}

public struct Discovery: Sendable, Hashable {
    public var newReleases: [CatalogAlbum] = []
    public var forYou: [CatalogTrack] = []
    public var artists: [CatalogArtist] = []
    public var albums: [CatalogAlbum] = []
    public var playlists: [DiscoveryPlaylist] = []

    public init() {}
}

public struct DiscoveryPlaylist: Sendable, Hashable, Identifiable {
    public let id: String
    public var title: String
    public var description: String?
    /// Filled in by `ContinuoService.discoveryPlaylist(id:)`.
    public var tracks: [CatalogTrack] = []
}

/// A song continuo fetches from Soulseek and streams while it downloads.
public struct PlayNowStream: Sendable, Hashable, Identifiable {
    public let id: String
    /// searching, downloading, streaming, ready, failed
    public var status: String
    public var playable: Bool
    public var percent: Double
    public var title: String
    public var artist: String?
    public var durationMs: Int?
    public var format: String?
    public var error: String?

    public var failed: Bool { status == "failed" }
}

public struct MusicRequest: Sendable, Hashable, Identifiable {
    public let id: Int
    public var title: String
    public var artist: String?
    public var year: Int?
    public var artworkURL: URL?
    /// requested, searching, downloading, importing, scanning, available, needs_attention, failed…
    public var status: String
    public var stillLooking: Bool
    public var stages: [String]
    public var stageIndex: Int
    public var percent: Float
    public var detail: String
    public var attention: String?
    public var error: String?
    /// The library album to open once it's in.
    public var albumID: String?
    public var createdAt: String?

    public var isDone: Bool { status == "available" }
    public var needsLook: Bool { status == "needs_attention" || status == "failed" }
}

public struct LyricLine: Sendable, Hashable {
    /// When the line starts; nil for unsynced lyrics.
    public var startMs: Int?
    public var text: String
}

public struct Lyrics: Sendable, Hashable {
    public var lines: [LyricLine]
    public var synced: Bool
    public var instrumental: Bool
    public var source: String?
}
