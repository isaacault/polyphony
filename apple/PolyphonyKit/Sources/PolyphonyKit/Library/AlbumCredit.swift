import Foundation

/// What an album page says under a song about who's on it, beyond the album's own artist (the
/// Android app's AlbumTrackDetails.credit, case for case: contract/cases/album-credit.json).
public enum AlbumCredit: Sendable, Equatable {
    /// Artists featured besides the album's artist: "ft. Future".
    case featuring(String)
    /// A song by someone else entirely (a compilation): its own artist.
    case other(String)

    // What joins a featured artist to the main one in a song's artist tag.
    private static let joiner = #"(?:\s|,|;|/|&|\+|\bx\b|\b(?:feat|ft)\b\.?|\bfeaturing\b|\bwith\b|\band\b)"#

    /// nil when the page already says it all: the album's artist, or featured artists the title names.
    public static func of(_ track: Track, albumArtist: String?) -> AlbumCredit? {
        let artist = (track.artistName ?? "").trimmingCharacters(in: .whitespaces)
        guard !artist.isEmpty else { return nil }
        let main = (albumArtist ?? "").trimmingCharacters(in: .whitespaces)
        if main.isEmpty || main.caseInsensitiveCompare("Various Artists") == .orderedSame { return .other(artist) }
        if artist.caseInsensitiveCompare(main) == .orderedSame { return nil }
        guard let at = artist.range(of: main, options: .caseInsensitive) else { return .other(artist) }
        var extra = String(artist[..<at.lowerBound]) + " " + String(artist[at.upperBound...])
        extra = extra.replacingOccurrences(of: "^\(joiner)+", with: "", options: [.regularExpression, .caseInsensitive])
        extra = extra.replacingOccurrences(of: "\(joiner)+$", with: "", options: [.regularExpression, .caseInsensitive])
        extra = extra.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        if extra.isEmpty || track.title.range(of: extra, options: .caseInsensitive) != nil { return nil }
        return .featuring(extra)
    }
}
