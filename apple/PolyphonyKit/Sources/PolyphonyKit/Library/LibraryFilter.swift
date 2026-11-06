import Foundation

/// What a library list is narrowed to; every field is an AND, the defaults filter nothing. The
/// Android app's LibraryFilter (its single-songs field is the Mac's Albums shelf).
public struct LibraryFilter: Sendable, Codable, Hashable {
    public var favouritesOnly = false
    /// 0: any; otherwise at least this many stars.
    public var minRating = 0
    public var downloadedOnly = false
    public var genre: String?
    /// Inclusive; either may be open.
    public var yearFrom: Int?
    public var yearTo: Int?

    public init() {}

    public var isActive: Bool { self != LibraryFilter() }

    private func yearMatches(_ year: Int?) -> Bool {
        if yearFrom == nil && yearTo == nil { return true }
        guard let year else { return false }
        if let yearFrom, year < yearFrom { return false }
        if let yearTo, year > yearTo { return false }
        return true
    }

    public func matches(_ album: Album, downloaded: Bool) -> Bool {
        if favouritesOnly && !album.starred { return false }
        if minRating > 0 && (album.rating ?? 0) < minRating { return false }
        if downloadedOnly && !downloaded { return false }
        if let genre, album.genre?.caseInsensitiveCompare(genre) != .orderedSame { return false }
        return yearMatches(album.year)
    }

    public func matches(_ track: Track, downloaded: Bool) -> Bool {
        if favouritesOnly && !track.starred { return false }
        if minRating > 0 && (track.rating ?? 0) < minRating { return false }
        if downloadedOnly && !downloaded { return false }
        if let genre, track.details?.genre?.caseInsensitiveCompare(genre) != .orderedSame { return false }
        return yearMatches(track.details?.year)
    }

    public func matches(_ artist: Artist) -> Bool {
        !favouritesOnly || artist.starred
    }

    /// For keeping in UserDefaults.
    public var stored: String {
        (try? JSONEncoder().encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    }

    public init(stored: String) {
        self = (try? JSONDecoder().decode(LibraryFilter.self, from: Data(stored.utf8))) ?? LibraryFilter()
    }
}
