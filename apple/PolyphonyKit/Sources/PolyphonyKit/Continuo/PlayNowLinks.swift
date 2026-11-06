import Foundation

/// Remembers the links speakers play Play now songs from: a link counts for [keep] (well inside
/// continuo's 12 hours, so a speaker never gets one about to lapse), which also keeps a queued song's
/// URL the same from arming to playing, and a stream continuo just refused isn't asked for again for
/// [retry]. The Android app's PlayNowLinkCache.
public struct PlayNowLinkCache: Sendable {
    public var keep: TimeInterval = 6 * 3600
    public var retry: TimeInterval = 30
    private var links: [String: (url: URL, at: Date)] = [:]
    private var failures: [String: Date] = [:]
    private static let maximum = 256

    public init() {}

    public func fresh(_ id: String, now: Date = Date()) -> URL? {
        guard let link = links[id], now.timeIntervalSince(link.at) < keep else { return nil }
        return link.url
    }

    public mutating func put(_ id: String, _ url: URL, now: Date = Date()) {
        if links.count >= Self.maximum, let oldest = links.min(by: { $0.value.at < $1.value.at })?.key { links[oldest] = nil }
        links[id] = (url, now)
        failures[id] = nil
    }

    public mutating func failed(_ id: String, now: Date = Date()) {
        if failures.count >= Self.maximum { failures = [:] }
        failures[id] = now
    }

    public func recentlyFailed(_ id: String, now: Date = Date()) -> Bool {
        failures[id].map { now.timeIntervalSince($0) < retry } ?? false
    }
}
