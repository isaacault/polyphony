import Foundation

/// What's downloaded for offline play, kept as JSON beside the files. Each entry keeps the track as
/// it was, so the Downloads page and the player work with no server at all.
public struct DownloadIndex: Sendable, Codable, Equatable {
    public struct Entry: Sendable, Codable, Equatable, Identifiable {
        public var id: String { track.id }
        public var track: Track
        /// The file's name inside the downloads folder.
        public var file: String
        public var bytes: Int64
        public var downloadedAt: Date
    }

    public private(set) var entries: [String: Entry] = [:]

    public init() {}

    public func contains(_ trackID: String) -> Bool { entries[trackID] != nil }

    public func file(for trackID: String) -> String? { entries[trackID]?.file }

    public var usedBytes: Int64 { entries.values.reduce(0) { $0 + $1.bytes } }

    public mutating func add(_ track: Track, file: String, bytes: Int64, at date: Date = Date()) {
        entries[track.id] = Entry(track: track, file: file, bytes: bytes, downloadedAt: date)
    }

    /// The removed entry's file, for the caller to delete.
    @discardableResult
    public mutating func remove(_ trackID: String) -> String? {
        entries.removeValue(forKey: trackID)?.file
    }

    /// Downloaded songs grouped into their albums, newest download first, songs in album order.
    public var albums: [DownloadedAlbum] {
        Dictionary(grouping: entries.values) { $0.track.albumID ?? "album:\($0.track.albumName ?? "")" }
            .map { key, group in
                let tracks = group.map(\.track).sorted {
                    ($0.discNumber ?? 1, $0.trackNumber ?? .max, $0.title) < ($1.discNumber ?? 1, $1.trackNumber ?? .max, $1.title)
                }
                return DownloadedAlbum(id: key, name: tracks.first?.albumName ?? "Unknown album",
                                       artist: tracks.first?.artistName, coverArtID: tracks.first?.coverArtID ?? tracks.first?.albumID,
                                       tracks: tracks, bytes: group.reduce(0) { $0 + $1.bytes },
                                       newest: group.map(\.downloadedAt).max() ?? .distantPast)
            }
            .sorted { $0.newest > $1.newest }
    }

    /// How many of an album's songs are downloaded: the album page's download button says so.
    public func count(of tracks: [Track]) -> Int {
        tracks.filter { contains($0.id) }.count
    }

    /// A file name for a track's download: its id (made safe) and the format's extension.
    public static func fileName(for track: Track, format: String?) -> String {
        let safe = track.id.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "_" }
        let ext = (format ?? track.container ?? "audio").lowercased().filter { $0.isLetter || $0.isNumber }
        return String(safe) + "." + (ext.isEmpty ? "audio" : ext)
    }
}

public struct DownloadedAlbum: Sendable, Equatable, Identifiable {
    public let id: String
    public var name: String
    public var artist: String?
    public var coverArtID: String?
    public var tracks: [Track]
    public var bytes: Int64
    public var newest: Date
}
