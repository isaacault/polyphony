import Foundation

/// A standing instruction: keep everything matching [target] downloaded (ported from the Android
/// app's download rules). Rules are looked at again after sign-in, every hour, and when they change.
public struct DownloadRule: Sendable, Codable, Equatable, Identifiable {
    public enum Target: Sendable, Codable, Hashable {
        case favourites
        case playlist(id: String, name: String)
        case album(id: String, name: String)
        case artist(id: String, name: String)
        case genre(String)
        case recentlyAdded(albums: Int)
        case mostPlayed(albums: Int)
        case recentlyPlayed(albums: Int)

        public var label: String {
            switch self {
            case .favourites: "Favourite songs"
            case let .playlist(_, name): "Playlist · \(name)"
            case let .album(_, name): "Album · \(name)"
            case let .artist(_, name): "Artist · \(name)"
            case let .genre(name): "Genre · \(name)"
            case let .recentlyAdded(n): "Last \(n) albums added"
            case let .mostPlayed(n): "\(n) most played albums"
            case let .recentlyPlayed(n): "Last \(n) albums played"
            }
        }
    }

    public let id: String
    public var target: Target
    public var enabled: Bool
    /// A cap on how many songs it may hold, first ones first; 0 = none.
    public var maxTracks: Int

    public init(id: String = UUID().uuidString, target: Target, enabled: Bool = true, maxTracks: Int = 0) {
        self.id = id
        self.target = target
        self.enabled = enabled
        self.maxTracks = maxTracks
    }
}

extension DownloadRule {
    /// The songs the rule wants, in its own order (best first), before [maxTracks].
    public func tracks(from library: SubsonicClient) async throws -> [Track] {
        func albumsTracks(_ albums: [Album]) async throws -> [Track] {
            var out: [Track] = []
            for album in albums { out += try await library.album(id: album.id).tracks }
            return out
        }
        switch target {
        case .favourites: return try await library.starred().tracks
        case let .playlist(id, _): return try await library.playlist(id: id).tracks
        case let .album(id, _): return try await library.album(id: id).tracks
        case let .artist(id, _): return try await albumsTracks(try await library.artist(id: id).1)
        case let .genre(name): return try await library.songs(.genre(name), size: 500)
        case let .recentlyAdded(n): return try await albumsTracks(try await library.albums(.newest, size: n))
        case let .mostPlayed(n): return try await albumsTracks(try await library.albums(.frequent, size: n))
        case let .recentlyPlayed(n): return try await albumsTracks(try await library.albums(.recent, size: n))
        }
    }
}

/// What to change so the downloads match the rules (Android's RuleDiff, without quality profiles:
/// the Mac keeps original files).
public struct RulePlan: Sendable, Equatable {
    public struct Fetch: Sendable, Equatable {
        public let track: Track
        public let ruleID: String
    }

    public var fetch: [Fetch] = []
    /// Rule-kept downloads nothing wants any more.
    public var remove: [String] = []
    /// Downloads to hand to another rule (track id, rule id): the one that kept it no longer does.
    public var retag: [(String, String)] = []

    public var isEmpty: Bool { fetch.isEmpty && remove.isEmpty && retag.isEmpty }

    public static func == (a: RulePlan, b: RulePlan) -> Bool {
        a.fetch == b.fetch && a.remove == b.remove && a.retag.map { [$0.0, $0.1] } == b.retag.map { [$0.0, $0.1] }
    }

    /// [perRule] is each enabled rule with its songs, or nil when they couldn't be looked up. A rule
    /// that couldn't say (nil, or nothing at all while it keeps downloads: a deleted playlist, a
    /// library mid-scan) is held: nothing of its is removed. Deleting the rule is how its files go.
    public static func compute(perRule: [(DownloadRule, [Track]?)], index: DownloadIndex,
                               removeUnmatched: Bool = true, active: Set<String> = []) -> RulePlan {
        let owning = Set(index.entries.values.compactMap(\.ruleID))
        var held: Set<String> = []
        var wanted: [String: (Track, String)] = [:]
        var order: [String] = []
        for (rule, tracks) in perRule where rule.enabled {
            guard let tracks, !(tracks.isEmpty && owning.contains(rule.id)) else {
                held.insert(rule.id)
                continue
            }
            for track in rule.maxTracks > 0 ? Array(tracks.prefix(rule.maxTracks)) : tracks where wanted[track.id] == nil {
                wanted[track.id] = (track, rule.id)
                order.append(track.id)
            }
        }
        var plan = RulePlan()
        for id in order {
            guard let want = wanted[id] else { continue }
            let (track, ruleID) = want
            if let have = index.entries[id] {
                if !have.pinned, have.ruleID != ruleID, !held.contains(have.ruleID ?? "") { plan.retag.append((id, ruleID)) }
            } else if !active.contains(id) {
                plan.fetch.append(Fetch(track: track, ruleID: ruleID))
            }
        }
        if removeUnmatched {
            plan.remove = index.entries.values
                .filter { entry in !entry.pinned && wanted[entry.id] == nil && !held.contains(entry.ruleID ?? "") }
                .map(\.id).sorted()
        }
        return plan
    }
}
