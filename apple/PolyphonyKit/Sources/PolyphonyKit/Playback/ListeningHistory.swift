import Foundation

/// The collection a queue was started from: Home's "Continue listening" shows these and resumes each
/// where it was left. As the Android app's PlayContext.
public struct PlayContext: Sendable, Hashable, Codable {
    public enum Kind: String, Sendable, Codable { case album, artist, playlist, genre, favourites }

    public var kind: Kind
    /// Album, artist or playlist id; genre name; "" for favourites.
    public var id: String
    public var title: String
    public var subtitle: String?
    public var coverArtID: String?

    public init(kind: Kind, id: String, title: String, subtitle: String? = nil, coverArtID: String? = nil) {
        self.kind = kind
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.coverArtID = coverArtID
    }

    public static func album(_ a: Album) -> PlayContext {
        PlayContext(kind: .album, id: a.id, title: a.name, subtitle: a.artistName, coverArtID: a.coverArtID ?? a.id)
    }

    public static func playlist(_ p: Playlist) -> PlayContext {
        PlayContext(kind: .playlist, id: p.id, title: p.name, subtitle: p.owner, coverArtID: p.coverArtID)
    }

    public var key: String { "\(kind.rawValue):\(id)" }
}

/// A context with the listener's last place in it.
public struct RecentContext: Sendable, Hashable, Codable {
    public var context: PlayContext
    /// The track that was playing; nil once the context was played to its end.
    public var trackID: String?
    public var trackIndex: Int
    public var positionMs: Int
    public var updatedAt: Date
}

/// Where a rebuilt queue starts so it picks up where a RecentContext left off: the Android app's
/// ResumePoint, tested against the same contract/cases/resume.json.
public struct ResumePoint: Sendable, Equatable {
    public var index: Int
    public var positionMs: Int

    /// Within this much of a track's end, move on to the next instead of replaying the tail.
    public static let nearEndMs = 3_000
    public static let start = ResumePoint(index: 0, positionMs: 0)

    public init(index: Int, positionMs: Int) {
        self.index = index
        self.positionMs = positionMs
    }

    /// Finds the remembered track by id (its index may have shifted since); falls back to the
    /// remembered index when it's gone, and to the start once the context was finished.
    public static func of(_ tracks: [Track], _ entry: RecentContext?) -> ResumePoint {
        guard !tracks.isEmpty, let entry, let trackID = entry.trackID else { return start }
        guard let index = tracks.firstIndex(where: { $0.id == trackID }) else {
            return ResumePoint(index: min(max(entry.trackIndex, 0), tracks.count - 1), positionMs: 0)
        }
        let durationMs = Int(tracks[index].duration * 1000)
        var position = max(entry.positionMs, 0)
        if durationMs > 0 { position = min(position, durationMs) }
        let nearEnd = durationMs > 0 && durationMs - position <= nearEndMs
        if !nearEnd { return ResumePoint(index: index, positionMs: position) }
        return index < tracks.count - 1 ? ResumePoint(index: index + 1, positionMs: 0) : start
    }
}

/// Recently played collections, newest first, at most [limit], kept between launches.
public struct ListeningHistory: Sendable, Codable, Equatable {
    public private(set) var entries: [RecentContext] = []
    public var limit = 20

    public init() {}

    public mutating func record(_ context: PlayContext, trackID: String?, index: Int, positionMs: Int, at date: Date = Date()) {
        entries.removeAll { $0.context.key == context.key }
        entries.insert(RecentContext(context: context, trackID: trackID, trackIndex: index, positionMs: positionMs,
                                     updatedAt: date), at: 0)
        if entries.count > limit { entries.removeLast(entries.count - limit) }
    }

    public mutating func remove(_ context: PlayContext) {
        entries.removeAll { $0.context.key == context.key }
    }

    public func entry(for context: PlayContext) -> RecentContext? {
        entries.first { $0.context.key == context.key }
    }
}
