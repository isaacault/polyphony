import Foundation

/// What can be in the queue: a library track, a song continuo is fetching (Play now), or a catalogue
/// song not looked up yet: when the queue reaches it, it becomes the library's copy or a Play now
/// (one at a time, as the Android app's queue of a discovery playlist).
public enum QueueEntry: Sendable, Hashable {
    case track(Track)
    case playNow(PlayNowStream, CatalogTrack)
    case catalog(CatalogTrack)

    /// The catalogue song behind a Play now or not-yet-fetched entry.
    public var catalogTrack: CatalogTrack? {
        switch self {
        case .track: nil
        case let .playNow(_, c), let .catalog(c): c
        }
    }

    public var title: String {
        switch self {
        case let .track(t): t.title
        case let .playNow(_, c), let .catalog(c): c.title
        }
    }

    public var artist: String? {
        switch self {
        case let .track(t): t.artistName
        case let .playNow(_, c), let .catalog(c): c.artist
        }
    }

    public var album: String? {
        switch self {
        case let .track(t): t.albumName
        case let .playNow(_, c), let .catalog(c): c.albumTitle
        }
    }

    public var duration: TimeInterval {
        switch self {
        case let .track(t): t.duration
        case let .playNow(s, c): TimeInterval(s.durationMs ?? c.durationMs ?? 0) / 1000
        case let .catalog(c): TimeInterval(c.durationMs ?? 0) / 1000
        }
    }

    public var libraryTrack: Track? {
        if case let .track(t) = self { return t }
        return nil
    }
}

public enum RepeatMode: String, Sendable, CaseIterable {
    case off, all, one
}

/// The play queue: entries in their own order, a play order over them (shuffled or not), and where
/// playback is. A value type, so every rule is a pure function, tested on its own.
public struct PlayQueue: Sendable, Equatable {
    /// Each entry gets an id of its own, so the same song twice is two rows.
    public struct Item: Sendable, Hashable, Identifiable {
        public let id: UUID
        public var entry: QueueEntry
        /// A song you added (Play next, Add to queue): "Next in queue", before the list resumes.
        public var queued: Bool

        public init(_ entry: QueueEntry, id: UUID = UUID(), queued: Bool = false) {
            self.id = id
            self.entry = entry
            self.queued = queued
        }
    }

    public private(set) var items: [Item] = []
    /// Indices into [items], in the order they play.
    public private(set) var order: [Int] = []
    /// Position in [order] of what's playing; nil for an empty queue.
    public private(set) var position: Int?
    public private(set) var shuffled = false
    public var repeatMode: RepeatMode = .off
    public var title: String?
    /// The collection this queue plays (an album, a playlist…), for Continue listening.
    public var context: PlayContext?

    public init() {}

    public init(_ entries: [QueueEntry], startAt index: Int = 0, shuffle: Bool = false, title: String? = nil) {
        items = entries.map { Item($0) }
        order = Array(items.indices)
        position = items.isEmpty ? nil : min(max(index, 0), items.count - 1)
        self.title = title
        if shuffle { setShuffle(true) }
    }

    public var isEmpty: Bool { items.isEmpty }

    public var current: Item? {
        guard let position, order.indices.contains(position) else { return nil }
        return items[order[position]]
    }

    /// The queue as it plays: what's playing, then what's next.
    public var upNext: [Item] {
        guard let position else { return [] }
        return order.dropFirst(position + 1).map { items[$0] }
    }

    /// What's already played, oldest first.
    /// How many of the songs right after the current one are yours ("Next in queue", as on
    /// Spotify): they play before the list you pressed play on resumes.
    public var queuedNext: Int {
        guard let position else { return 0 }
        var n = 0
        for i in order.dropFirst(position + 1) {
            guard items[i].queued else { break }
            n += 1
        }
        return n
    }

    /// Your songs still to come, to carry over when something new is played.
    public var waitingQueued: [QueueEntry] {
        Array(upNext.prefix(queuedNext).map(\.entry))
    }

    public var history: [Item] {
        guard let position else { return [] }
        return order.prefix(position).map { items[$0] }
    }

    /// What plays after the current entry ends by itself (repeat one replays it; repeat all wraps).
    public var nextOnEnd: Item? {
        guard let position else { return nil }
        if repeatMode == .one { return current }
        if position + 1 < order.count { return items[order[position + 1]] }
        return repeatMode == .all ? order.first.map { items[$0] } : nil
    }

    // MARK: Moving through it

    /// After the current entry ends by itself: false when the queue is done.
    @discardableResult
    public mutating func advanceOnEnd() -> Bool {
        guard let p = position else { return false }
        if repeatMode == .one { return true }
        if p + 1 < order.count {
            position = p + 1
            return true
        }
        if repeatMode == .all, !order.isEmpty {
            position = 0
            return true
        }
        return false
    }

    /// The Next button: always moves (wrapping when repeating), even under repeat one.
    @discardableResult
    public mutating func next() -> Bool {
        guard let p = position else { return false }
        if p + 1 < order.count {
            position = p + 1
            return true
        }
        if repeatMode != .off, !order.isEmpty {
            position = 0
            return true
        }
        return false
    }

    /// The Previous button's move (the player decides first whether to restart the song instead).
    @discardableResult
    public mutating func previous() -> Bool {
        guard let p = position else { return false }
        if p > 0 {
            position = p - 1
            return true
        }
        if repeatMode == .all, !order.isEmpty {
            position = order.count - 1
            return true
        }
        return false
    }

    /// Jump to an item anywhere in the queue.
    public mutating func jump(to id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }), let at = order.firstIndex(of: index) else { return }
        position = at
    }

    // MARK: Changing it

    /// Shuffle keeps what's playing and shuffles only the rest; unshuffling goes back to the
    /// entries' own order, from the current one.
    public mutating func setShuffle(_ on: Bool) {
        guard on != shuffled else { return }
        shuffled = on
        guard let currentIndex = current.flatMap({ c in items.firstIndex(where: { $0.id == c.id }) }) else {
            order = on ? Array(items.indices).shuffled() : Array(items.indices)
            return
        }
        if on {
            // Shuffle mixes the list, never your queue: yours stay straight after the current song.
            let yours = upNext.prefix(queuedNext).compactMap { q in items.firstIndex(where: { $0.id == q.id }) }
            let rest = items.indices.filter { $0 != currentIndex && !yours.contains($0) }
            order = [currentIndex] + yours + rest.shuffled()
            position = 0
        } else {
            order = Array(items.indices)
            position = currentIndex
        }
    }

    /// Play next: the front of your queue, straight after the current song (contract/cases/queue.json).
    public mutating func playNext(_ entries: [QueueEntry]) {
        insert(entries, afterPosition: position ?? -1, queued: true)
    }

    /// Add to queue: the back of your queue, before the list you pressed play on resumes (with none
    /// of yours waiting, straight after the current song, as Play next).
    public mutating func append(_ entries: [QueueEntry]) {
        guard let position else {
            insert(entries, afterPosition: order.count - 1, queued: true)
            return
        }
        insert(entries, afterPosition: position + queuedNext, queued: true)
    }

    /// Something new played while some of yours wait: they go straight after its first song.
    public mutating func carryQueued(_ entries: [QueueEntry]) {
        guard let position, !entries.isEmpty else { return }
        insert(entries, afterPosition: position, queued: true)
    }

    private mutating func insert(_ entries: [QueueEntry], afterPosition p: Int, queued: Bool = false) {
        guard !entries.isEmpty else { return }
        let start = items.count
        items += entries.map { Item($0, queued: queued) }
        let added = Array(start..<items.count)
        order.insert(contentsOf: added, at: p + 1)
        if position == nil { position = 0 }
    }

    /// Remove an item; removing what's playing moves to what was next (or back, at the end).
    public mutating func remove(_ id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }), let at = order.firstIndex(of: index) else { return }
        order.remove(at: at)
        items.remove(at: index)
        order = order.map { $0 > index ? $0 - 1 : $0 }
        guard let p = position else { return }
        if order.isEmpty {
            position = nil
        } else if at < p {
            position = p - 1
        } else if at == p {
            position = min(p, order.count - 1)
        }
    }

    /// Move an upcoming item to another place in the upcoming list ([to] counts within [upNext]).
    public mutating func moveUpNext(from: Int, to: Int) {
        guard let p = position else { return }
        let start = p + 1
        guard order.indices.contains(start + from) else { return }
        let moved = order.remove(at: start + from)
        order.insert(moved, at: min(start + max(to, 0), order.count))
    }

    /// Everything after the current entry goes.
    public mutating func clearUpNext() {
        guard let p = position else { return }
        let keep = Set(order.prefix(p + 1))
        let kept = items.indices.filter { keep.contains($0) }
        let remap = Dictionary(uniqueKeysWithValues: kept.enumerated().map { ($1, $0) })
        items = kept.map { items[$0] }
        order = order.prefix(p + 1).compactMap { remap[$0] }
    }

    /// Replace an entry in place (a Play now stream whose status moved on).
    public mutating func update(_ id: UUID, to entry: QueueEntry) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        items[index].entry = entry
    }
}

extension PlayQueue {
    /// What's kept between launches so the app opens where it left off: the library tracks in their
    /// play order and where in them it was. Play now streams aren't kept (they're temporary).
    public struct Snapshot: Sendable, Codable, Equatable {
        public var tracks: [Track]
        public var index: Int
        public var position: TimeInterval
        public var repeatMode: String
        public var title: String?
        public var context: PlayContext?
    }

    public func snapshot(position: TimeInterval) -> Snapshot? {
        guard let p = self.position else { return nil }
        let played = order.map { items[$0] }
        let kept = played.enumerated().compactMap { i, item in item.entry.libraryTrack.map { (i, $0) } }
        guard !kept.isEmpty else { return nil }
        // Where the current one lands once the Play now entries are left out (or the nearest before it).
        let index = kept.lastIndex(where: { $0.0 <= p }) ?? 0
        let samePlace = kept[index].0 == p
        return Snapshot(tracks: kept.map(\.1), index: index, position: samePlace ? position : 0,
                        repeatMode: repeatMode.rawValue, title: title, context: context)
    }

    /// The queue as the snapshot left it, unshuffled (its order is already the play order).
    public init(snapshot: Snapshot) {
        self.init(snapshot.tracks.map(QueueEntry.track), startAt: snapshot.index, shuffle: false, title: snapshot.title)
        repeatMode = RepeatMode(rawValue: snapshot.repeatMode) ?? .off
        context = snapshot.context
    }
}

/// When a play counts, as Last.fm counts it: half the song or four minutes, whichever comes first,
/// and never for songs under 30 seconds.
/// When a play counts (a scrobble), as the Android app's PlaybackReporter has it: after half the song
/// or four minutes, whichever comes first, never under ten seconds; four minutes when the length isn't
/// known.
public enum ScrobbleRule {
    public static func counts(played: TimeInterval, duration: TimeInterval, threshold: Double = 0.5) -> Bool {
        let needed = duration > 0 ? min(duration * threshold, 240) : 240
        return played >= max(needed, 10)
    }
}
