import Foundation

/// The whole album a library album is of, from the catalogue (continuo's library tracklist).
public struct LibraryAlbumTracklist: Sendable, Hashable {
    public var releaseGroupID: String
    public var tracks: [CatalogTracklistEntry]

    public init(releaseGroupID: String, tracks: [CatalogTracklistEntry]) {
        self.releaseGroupID = releaseGroupID
        self.tracks = tracks
    }
}

/// One row of an album page: a song you have, or one of the album's you don't.
public enum AlbumRow: Sendable, Hashable {
    case have(Track, disc: Int)
    /// Not in the library: played through Play now, or requested.
    case missing(CatalogTrack, disc: Int)

    public var disc: Int {
        switch self {
        case let .have(_, d), let .missing(_, d): d
        }
    }
}

/// A library album as the whole album it's of (the Android app's AlbumTracklist, case for case:
/// contract/cases/album-tracklist.json): the album's order, each song the library has in its place
/// (matched by title), the rest missing; library songs the tracklist doesn't name at the end of their
/// disc. Without a tracklist, one no longer than the library's songs, or one matching none of them
/// (the wrong release), it's the library's songs alone.
public enum AlbumTracklist {
    public static func rows(library: [Track], whole: LibraryAlbumTracklist?, artist: String?, albumTitle: String?,
                            artworkURL: URL? = nil) -> [AlbumRow] {
        let own = library.map { AlbumRow.have($0, disc: $0.discNumber ?? 1) }
        guard let whole, !whole.tracks.isEmpty, whole.tracks.count > library.count else { return own }
        var byTitle = Dictionary(grouping: library, by: { key($0.title) })
        var used: Set<String> = []
        var out: [AlbumRow] = []
        for t in whole.tracks {
            let k = key(t.title)
            if var list = byTitle[k], !list.isEmpty {
                let have = list.removeFirst()
                byTitle[k] = list
                used.insert(have.id)
                out.append(.have(have, disc: t.disc))
            } else {
                out.append(.missing(catalogTrack(t, whole.releaseGroupID, artist, albumTitle, artworkURL), disc: t.disc))
            }
        }
        if used.isEmpty && !library.isEmpty { return own }
        let rest = own.filter { if case let .have(t, _) = $0 { return !used.contains(t.id) }; return false }
        // Stable: the album's order within a disc, strays last.
        return (out + rest).enumerated().sorted { ($0.element.disc, $0.offset) < ($1.element.disc, $1.offset) }.map(\.element)
    }

    /// "Visit Croatia (feat. X)" and "visit croatia" are one song.
    static func key(_ title: String) -> String {
        let plain = title.lowercased().replacingOccurrences(of: #"\s*[(\[](?:feat|ft|featuring|with)\b[^)\]]*[)\]]"#,
                                                            with: "", options: .regularExpression)
        return String(plain.filter { $0.isLetter || $0.isNumber })
    }

    private static func catalogTrack(_ t: CatalogTracklistEntry, _ rg: String, _ artist: String?, _ album: String?,
                                     _ art: URL?) -> CatalogTrack {
        CatalogTrack(key: t.recordingID.map { "mb:rec:\($0)" } ?? "rg:\(rg):\(t.disc):\(t.position)", title: t.title,
                     artist: artist, durationMs: t.durationMs, albumTitle: album, albumReleaseGroupID: rg,
                     availability: .requestable, recordingID: t.recordingID, artworkURL: art)
    }
}
