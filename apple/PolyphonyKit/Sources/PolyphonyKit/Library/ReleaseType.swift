import Foundation

/// What kind of release an album is, as MusicBrainz types it. Only the primary type decides;
/// secondary ones (compilation, live, remix…) don't. The Android app's ReleaseType, tested from the
/// same cases (contract/cases/release-type.json). In page order: artist pages group by it.
public enum ReleaseType: String, Sendable, Codable, Hashable, CaseIterable, Comparable {
    case album = "ALBUM", ep = "EP", single = "SINGLE", other = "OTHER"

    /// The type from a server's release types (OpenSubsonic `releaseTypes`, e.g. ["Album",
    /// "Compilation"]); nil when none is a primary type (untagged): shown as an album.
    public static func of(_ types: [String]?) -> ReleaseType? {
        for type in types ?? [] {
            switch type.trimmingCharacters(in: .whitespaces).lowercased() {
            case "album": return .album
            case "ep": return .ep
            case "single": return .single
            case "broadcast", "other": return .other
            default: continue
            }
        }
        return nil
    }

    /// "Single" or "EP" before the artist; nil for an album.
    public var shortLabel: String? {
        switch self {
        case .single: "Single"
        case .ep: "EP"
        case .album, .other: nil
        }
    }

    /// An artist page's heading for its group.
    public var sectionTitle: String {
        switch self {
        case .album: "Albums"
        case .ep: "EPs"
        case .single: "Singles"
        case .other: "Other Releases"
        }
    }

    public static func < (a: ReleaseType, b: ReleaseType) -> Bool {
        allCases.firstIndex(of: a)! < allCases.firstIndex(of: b)!
    }
}

extension Album {
    /// A single or an EP: listed apart from the full albums.
    public var isShortRelease: Bool { releaseType == .single || releaseType == .ep }

    /// Under the title: "Single · Jorja Smith", or the artist alone for an album.
    public var subtitle: String {
        let artist = artistName ?? ""
        guard let kind = releaseType?.shortLabel else { return artist }
        return artist.isEmpty ? kind : "\(kind) · \(artist)"
    }

    /// [albums] grouped Albums, EPs, Singles (untagged ones with the albums), each newest first;
    /// one group when they're all albums.
    public static func grouped(_ albums: [Album]) -> [(type: ReleaseType, albums: [Album])] {
        let groups = Dictionary(grouping: albums) { $0.releaseType ?? .album }
        return groups.keys.sorted().map { type in
            (type, groups[type]!.sorted { ($0.year ?? 0) > ($1.year ?? 0) })
        }
    }
}
