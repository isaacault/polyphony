import Foundation

/// One self-contained backup: the app's settings and its servers, as a single JSON file the user keeps.
/// The file holds a plain Subsonic server's password, so a restore is complete; it's the user's own
/// private file. The exception is a continuo server: its credential is this install's device session,
/// which must never be shared with a second Mac, so it's left out and a restored copy signs in again
/// (the Android app's BackupManager).
public struct AppBackup: Sendable, Codable, Equatable {
    public enum Value: Sendable, Codable, Equatable {
        case bool(Bool), int(Int), double(Double), string(String), data(Data)

        private enum CodingKeys: String, CodingKey { case type, value }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            switch try c.decode(String.self, forKey: .type) {
            case "bool": self = .bool(try c.decode(Bool.self, forKey: .value))
            case "int": self = .int(try c.decode(Int.self, forKey: .value))
            case "double": self = .double(try c.decode(Double.self, forKey: .value))
            case "data": self = .data(try c.decode(Data.self, forKey: .value))
            default: self = .string(try c.decode(String.self, forKey: .value))
            }
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            switch self {
            case let .bool(v): try c.encode("bool", forKey: .type); try c.encode(v, forKey: .value)
            case let .int(v): try c.encode("int", forKey: .type); try c.encode(v, forKey: .value)
            case let .double(v): try c.encode("double", forKey: .type); try c.encode(v, forKey: .value)
            case let .string(v): try c.encode("string", forKey: .type); try c.encode(v, forKey: .value)
            case let .data(v): try c.encode("data", forKey: .type); try c.encode(v, forKey: .value)
            }
        }
    }

    public static let currentVersion = 1

    public var version = AppBackup.currentVersion
    /// Which app wrote it: a phone's backup has another shape.
    public var app = "polyphony-mac"
    public var settings: [String: Value]
    public var servers: [SavedServer]

    /// Settings kept by these prefixes go in; a server's queue, history and the like stay behind.
    public static let settingPrefixes = ["appearance.", "playback.", "streaming.", "feedback.", "downloads.", "library.",
                                         "offline.", "sidebar.", "albums.", "artists.", "playlists.", "songs.", "receiver."]

    public static func isSetting(_ key: String) -> Bool { settingPrefixes.contains { key.hasPrefix($0) } }

    public init(settings: [String: Value], servers: [SavedServer]) {
        self.settings = settings.filter { Self.isSetting($0.key) }
        self.servers = servers.map(Self.withoutDeviceSession)
    }

    /// A value UserDefaults holds, as a backup value; nil for what doesn't belong in one.
    public static func value(_ any: Any) -> Value? {
        switch any {
        case let v as Data: return .data(v)
        case let v as String: return .string(v)
        case let v as NSNumber:
            // A boolean is an NSNumber too: its type says which it holds.
            let type = String(cString: v.objCType)
            if type == "c" || type == "B" { return .bool(v.boolValue) }
            return type == "d" || type == "f" ? .double(v.doubleValue) : .int(v.intValue)
        default: return nil
        }
    }

    public static func withoutDeviceSession(_ server: SavedServer) -> SavedServer {
        guard server.kind == .continuo else { return server }
        var stripped = server
        stripped.session = nil
        stripped.subsonicPassword = ""
        return stripped
    }

    /// Whether this server came from a backup without what it needs to sign in.
    public static func needsSignIn(_ server: SavedServer) -> Bool {
        server.kind == .continuo && server.session == nil
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(self)
    }

    public enum RestoreError: Error, CustomStringConvertible {
        case notABackup
        case fromAnotherApp(String)

        public var description: String {
            switch self {
            case .notABackup: "That file isn't a Polyphony backup."
            case let .fromAnotherApp(app): "That backup comes from \(app): only a backup from Polyphony for Mac restores here."
            }
        }
    }

    public static func decoded(_ data: Data) throws -> AppBackup {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        // A phone's backup has `settings` and `servers` too, in another shape: say so, not "corrupt".
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], object["version"] != nil, object["app"] == nil {
            throw RestoreError.fromAnotherApp("Polyphony for Android")
        }
        guard let backup = try? decoder.decode(AppBackup.self, from: data) else { throw RestoreError.notABackup }
        if backup.app != "polyphony-mac" { throw RestoreError.fromAnotherApp(backup.app) }
        return backup
    }

    /// The server list after restoring: the backup's servers are added, nothing already here is lost,
    /// the one showing stays showing. A continuo server keeps this install's session when it's here;
    /// one that isn't can't be used until it's signed in to, so it's named instead of added.
    public func restored(into list: ServerList) -> (list: ServerList, needSignIn: [SavedServer]) {
        var out = list
        let showing = list.activeKey
        var missing: [SavedServer] = []
        for server in servers {
            if Self.needsSignIn(server) {
                if list.server(server.key) == nil { missing.append(server) }
            } else if list.server(server.key) != nil {
                out.update(server)
            } else {
                out.add(server)
            }
        }
        if let showing { _ = out.activate(showing) }
        return (out, missing)
    }
}
