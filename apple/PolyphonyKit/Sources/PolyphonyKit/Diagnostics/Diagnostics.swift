import Foundation

/// What went on lately (errors, sign-ins, speakers, downloads), for the diagnostics report: the last
/// [capacity] lines. Lines are scrubbed as they come in: a URL keeps its scheme, host and path but
/// loses its query, where Subsonic's token and salt travel.
public final class DiagnosticLog: @unchecked Sendable {
    public static let shared = DiagnosticLog()

    private let lock = NSLock()
    private var lines: [String] = []
    private let capacity: Int
    private let now: @Sendable () -> Date

    public init(capacity: Int = 500, now: @escaping @Sendable () -> Date = Date.init) {
        self.capacity = capacity
        self.now = now
    }

    public func log(_ area: String, _ message: String) {
        let line = "\(Diagnostics.timestamp(now())) [\(area)] \(Self.scrub(message))"
        lock.withLock {
            lines.append(line)
            if lines.count > capacity { lines.removeFirst(lines.count - capacity) }
        }
    }

    public var recent: [String] { lock.withLock { lines } }

    /// Query strings out of every URL in [text].
    public static func scrub(_ text: String) -> String {
        guard let pattern = try? NSRegularExpression(pattern: #"(https?://[^\s?#"']+)\?[^\s"']*"#) else { return text }
        let range = NSRange(text.startIndex..., in: text)
        return pattern.stringByReplacingMatches(in: text, range: range, withTemplate: "$1?…")
    }
}

/// The report Help > Save Diagnostics writes: the app, the Mac, the servers (never their passwords,
/// tokens or sessions), the settings, downloads and the recent log.
public enum Diagnostics {
    public struct Server: Sendable {
        public var kind: String
        public var host: String
        public var user: String
        public var showing: Bool
        public var receiverAddress: String?
        public var libraryHost: String?

        public init(kind: String, host: String, user: String, showing: Bool, receiverAddress: String?, libraryHost: String?) {
            self.kind = kind
            self.host = host
            self.user = user
            self.showing = showing
            self.receiverAddress = receiverAddress
            self.libraryHost = libraryHost
        }
    }

    public static func report(at date: Date = Date(), app: String, system: String, servers: [Server],
                              settings: [(String, String)], sections: [(String, [String])] = [], log: [String]) -> String {
        var out = ["Polyphony diagnostics \(timestamp(date))", "App: \(app)", "Mac: \(system)", "", "== Servers =="]
        out += servers.isEmpty ? ["(none)"] : servers.map { s in
            "- \(s.kind) \(s.host) user=\(s.user)\(s.showing ? " (showing)" : "")"
                + " receiverAddress=\(s.receiverAddress.flatMap { $0.isEmpty ? nil : $0 } ?? "(not set)")"
                + (s.libraryHost.map { $0 != s.host ? " library=\($0)" : "" } ?? "")
        }
        out += ["", "== Settings =="] + settings.map { "\($0.0): \($0.1)" }
        for (title, lines) in sections { out += ["", "== \(title) =="] + (lines.isEmpty ? ["(none)"] : lines) }
        out += ["", "== Recent log (\(log.count) lines) =="] + log
        return out.joined(separator: "\n") + "\n"
    }

    static func timestamp(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss 'UTC'"
        return f.string(from: date)
    }
}
