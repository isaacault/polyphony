import Foundation

/// Who a track's artist tag names: "Drake feat. Rihanna & Future" is three artists. The same rules as
/// the Android app's ArtistCredits (shared/domain), for "Appears on".
public enum ArtistCredits {
    private static let joiner = #"\s*(?:,|;|/|&|\+|\s+x\s+|\s+(?:feat|ft)\.?\s+|\s+featuring\s+|\s+with\s+|\s+and\s+|\s+vs\.?\s+)\s*"#

    public static func names(_ tag: String?) -> [String] {
        guard let tag, !tag.isEmpty else { return [] }
        return split(tag).map { $0.trimmingCharacters(in: CharacterSet(charactersIn: " ()")) }.filter { !$0.isEmpty }
    }

    /// Whether [tag] credits [artist] as one of its artists, not just contains the name ("Rihanna
    /// Tribute Band" doesn't credit Rihanna). A name with a joiner of its own ("Simon & Garfunkel")
    /// counts when the tag holds it whole.
    public static func credits(_ tag: String?, _ artist: String) -> Bool {
        let name = artist.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, let tag, !tag.isEmpty else { return false }
        if names(tag).contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) { return true }
        guard names(name).count >= 2, let range = tag.range(of: name, options: .caseInsensitive) else { return false }
        let before = String(tag[..<range.lowerBound])
        let after = String(tag[range.upperBound...])
        return (before.trimmingCharacters(in: .whitespaces).isEmpty || matches(before, "(?:\(joiner))$"))
            && (after.trimmingCharacters(in: .whitespaces).isEmpty || matches(after, "^(?:\(joiner))"))
    }

    /// Albums by others that credit [artist] on a track, from tracks found by searching their name.
    public static func appearsOn(_ artist: Artist, among tracks: [Track]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for t in tracks where credits(t.artistName, artist.name) && t.artistID != artist.id {
            guard let album = t.albumID, !seen.contains(album) else { continue }
            seen.insert(album)
            out.append(album)
        }
        return out
    }

    private static func split(_ s: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: joiner, options: .caseInsensitive) else { return [s] }
        var parts: [String] = []
        var last = s.startIndex
        for m in regex.matches(in: s, range: NSRange(s.startIndex..., in: s)) {
            guard let r = Range(m.range, in: s) else { continue }
            parts.append(String(s[last..<r.lowerBound]))
            last = r.upperBound
        }
        parts.append(String(s[last...]))
        return parts
    }

    private static func matches(_ s: String, _ pattern: String) -> Bool {
        s.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }
}
