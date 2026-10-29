import Foundation

/// What was searched for lately, newest first, for the empty search page (as the Android app's
/// recent searches): searching again moves a query to the top instead of listing it twice.
public struct RecentSearches: Sendable, Codable, Equatable {
    public private(set) var items: [String] = []
    public static let limit = 10

    public init(_ items: [String] = []) {
        self.items = Array(items.prefix(Self.limit))
    }

    public mutating func add(_ query: String) {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 2 else { return }
        items.removeAll { $0.caseInsensitiveCompare(text) == .orderedSame }
        items.insert(text, at: 0)
        if items.count > Self.limit { items.removeLast(items.count - Self.limit) }
    }

    public mutating func remove(_ query: String) {
        items.removeAll { $0 == query }
    }

    public mutating func clear() {
        items = []
    }
}
