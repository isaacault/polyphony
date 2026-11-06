import Foundation

// Which quality a song streams at, the Android app's rules (shared/domain's transcode package): the
// same profiles, the same resolution order, tested from the same cases (contract/cases/transcode.json).

/// Target codec for a profile. Raw values are the Kotlin enum's names.
public enum AudioCodec: String, Sendable, Codable, CaseIterable, Hashable {
    case original = "ORIGINAL", flac = "FLAC", opus = "OPUS", aac = "AAC", mp3 = "MP3", vorbis = "VORBIS"

    public var label: String {
        switch self {
        case .original: "Original"
        case .flac: "FLAC"
        case .opus: "Opus"
        case .aac: "AAC"
        case .mp3: "MP3"
        case .vorbis: "Ogg Vorbis"
        }
    }

    public var lossy: Bool {
        switch self {
        case .original, .flac: false
        case .opus, .aac, .mp3, .vorbis: true
        }
    }

    var typicalMaxKbps: Int {
        switch self {
        case .original, .flac: 0
        case .opus: 510
        case .aac, .mp3: 320
        case .vorbis: 500
        }
    }
}

/// How a song should be delivered. Caps of 0 mean unlimited; the original codec with no caps is a
/// passthrough.
public struct TranscodeProfile: Sendable, Codable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var codec: AudioCodec
    public var maxBitrateKbps: Int
    public var maxSampleRateHz: Int
    public var maxBitDepth: Int
    public var builtIn: Bool

    public init(id: String, name: String, codec: AudioCodec = .original, maxBitrateKbps: Int = 0, maxSampleRateHz: Int = 0,
                maxBitDepth: Int = 0, builtIn: Bool = false) {
        self.id = id
        self.name = name
        self.codec = codec
        self.maxBitrateKbps = maxBitrateKbps
        self.maxSampleRateHz = maxSampleRateHz
        self.maxBitDepth = maxBitDepth
        self.builtIn = builtIn
    }

    public var isPassthrough: Bool { codec == .original && maxBitrateKbps == 0 && maxSampleRateHz == 0 && maxBitDepth == 0 }

    /// Identifies the stream this profile produces: an edited profile never reuses a cached file.
    public var fingerprint: String { "\(id):\(codec.rawValue):\(maxBitrateKbps):\(maxSampleRateHz):\(maxBitDepth)" }

    /// How much quality the profile allows, to pick the more conservative of two.
    public var qualityRank: Int {
        if codec == .original && maxBitrateKbps == 0 { return Int.max }
        if codec == .flac && maxBitrateKbps == 0 { return Int.max - 1 }
        return maxBitrateKbps == 0 ? codec.typicalMaxKbps : maxBitrateKbps
    }

    public var summary: String {
        var s = codec == .original ? "Original" : codec.label
        if maxBitrateKbps > 0 { s += " · ≤\(maxBitrateKbps) kbps" }
        if maxSampleRateHz > 0 {
            let khz = Double(maxSampleRateHz) / 1000
            s += khz == khz.rounded() ? " · ≤\(Int(khz)) kHz" : " · ≤\(khz) kHz"
        }
        if maxBitDepth > 0 { s += " · ≤\(maxBitDepth)-bit" }
        return s
    }

    public static let originalID = "builtin.original"
    public static let balancedID = "builtin.balanced"

    public static let original = TranscodeProfile(id: originalID, name: "Original quality", builtIn: true)
    public static let losslessCapped = TranscodeProfile(id: "builtin.lossless48", name: "Lossless, capped to 16-bit/48 kHz", codec: .flac,
                                                        maxSampleRateHz: 48_000, maxBitDepth: 16, builtIn: true)
    public static let high = TranscodeProfile(id: "builtin.high", name: "High", codec: .opus, maxBitrateKbps: 160, builtIn: true)
    public static let balanced = TranscodeProfile(id: balancedID, name: "Balanced", codec: .opus, maxBitrateKbps: 128, builtIn: true)
    public static let saver = TranscodeProfile(id: "builtin.saver", name: "Data saver", codec: .opus, maxBitrateKbps: 64, builtIn: true)
    public static let castSafe = TranscodeProfile(id: "builtin.castsafe", name: "Cast compatible", codec: .mp3, maxBitrateKbps: 320, builtIn: true)

    public static let builtIns = [original, losslessCapped, high, balanced, saver, castSafe]

    public static func builtIn(_ id: String) -> TranscodeProfile? { builtIns.first { $0.id == id } }
}

public struct AdaptiveLatencySettings: Sendable, Codable, Hashable {
    public var enabled = true
    /// Caps the quality while the connection is considered degraded.
    public var highLatencyProfileID: String? = TranscodeProfile.balancedID
    /// This many rebuffers within [windowSeconds] trip the degraded state.
    public var stallThreshold = 3
    public var windowSeconds = 120
    /// A single stall longer than this trips it too.
    public var longStallMs = 4_000
    /// Seconds without stalls before quality comes back by itself. 0: only by hand.
    public var recoverAfterSeconds = 600
    public var applyToRemoteOutputs = true

    public init() {}
}

/// Resolution order (see [TranscodeResolver]): the remote output's own profile, the default for
/// remote outputs, the network rule, the default; then the latency cap.
public struct TranscodePolicy: Sendable, Codable, Hashable {
    public var defaultProfileID = TranscodeProfile.originalID
    /// nil: the default.
    public var wifiProfileID: String?
    public var cellularProfileID: String? = TranscodeProfile.balancedID
    /// Metered connections (a phone's hotspot) count as mobile data.
    public var meteredWifiAsCellular = true
    /// So does a network away from home: one where the server doesn't answer at its LAN address.
    public var awayWifiAsCellular = true
    public var castDefaultProfileID: String?
    public var adaptiveLatency = AdaptiveLatencySettings()
    /// A quality change restarts the current song at its place, instead of waiting for the next.
    public var reloadCurrentTrackOnChange = false

    public init() {}
}

public enum NetworkKind: String, Sendable, Hashable {
    case wifi = "WIFI", ethernet = "ETHERNET", cellular = "CELLULAR", other = "OTHER", none = "NONE"
}

public struct NetworkState: Sendable, Hashable {
    public var kind: NetworkKind
    public var isMetered: Bool

    public init(kind: NetworkKind, isMetered: Bool) {
        self.kind = kind
        self.isMetered = isMetered
    }

    public var isOnline: Bool { kind != .none }

    public static let unknown = NetworkState(kind: .other, isMetered: false)
    public static let offline = NetworkState(kind: .none, isMetered: false)
}

/// Where audio plays. A remote output carries the key its own profile is kept under.
public enum OutputTarget: Sendable, Hashable {
    case local
    case cast(deviceID: String, name: String)
    case dlna(udn: String, name: String)

    public var isRemote: Bool { self != .local }

    public var key: String {
        switch self {
        case .local: "local"
        case let .cast(id, _): "cast:\(id)"
        case let .dlna(udn, _): "dlna:\(udn)"
        }
    }

    public var displayName: String {
        switch self {
        case .local: "This Mac"
        case let .cast(_, name), let .dlna(_, name): name
        }
    }
}

public struct TranscodeContext: Sendable, Hashable {
    public var network: NetworkState
    public var target: OutputTarget
    public var latencyDegraded: Bool
    /// The profile chosen for [target], if the user chose one.
    public var deviceProfileID: String?
    /// On the home network; nil when it can't be told.
    public var atHome: Bool?

    public init(network: NetworkState, target: OutputTarget = .local, latencyDegraded: Bool = false,
                deviceProfileID: String? = nil, atHome: Bool? = nil) {
        self.network = network
        self.target = target
        self.latencyDegraded = latencyDegraded
        self.deviceProfileID = deviceProfileID
        self.atHome = atHome
    }
}

public struct TranscodeDecision: Sendable, Hashable {
    public enum Reason: String, Sendable, Hashable {
        case deviceOverride = "DEVICE_OVERRIDE", castDefault = "CAST_DEFAULT", wifiRule = "WIFI_RULE"
        case cellularRule = "CELLULAR_RULE", awayRule = "AWAY_RULE", `default` = "DEFAULT", lossySource = "LOSSY_SOURCE"

        public var label: String {
            switch self {
            case .deviceOverride: "Device setting"
            case .castDefault: "Cast default"
            case .wifiRule: "Wi-Fi rule"
            case .cellularRule: "Mobile data rule"
            case .awayRule: "Away from home"
            case .default: "Default"
            case .lossySource: "Already compressed"
            }
        }
    }

    public var profile: TranscodeProfile
    public var reason: Reason
    public var cappedByLatency = false

    public init(profile: TranscodeProfile, reason: Reason, cappedByLatency: Bool = false) {
        self.profile = profile
        self.reason = reason
        self.cappedByLatency = cappedByLatency
    }

    public var explanation: String { reason.label + (cappedByLatency ? " · capped for high latency" : "") }
}

public enum TranscodeResolver {
    public static func resolve(_ policy: TranscodePolicy, profiles: [TranscodeProfile], _ context: TranscodeContext) -> TranscodeDecision {
        func lookup(_ id: String?) -> TranscodeProfile? {
            guard let id else { return nil }
            return profiles.first { $0.id == id } ?? TranscodeProfile.builtIn(id)
        }
        let fallback = lookup(policy.defaultProfileID) ?? .original

        var decision: TranscodeDecision?
        if context.target.isRemote {
            if let p = lookup(context.deviceProfileID) {
                decision = TranscodeDecision(profile: p, reason: .deviceOverride)
            } else if let p = lookup(policy.castDefaultProfileID) {
                decision = TranscodeDecision(profile: p, reason: .castDefault)
            }
        }
        var result = decision ?? networkRule(policy, context, lookup, fallback)

        let latency = policy.adaptiveLatency
        if context.latencyDegraded, latency.enabled, !context.target.isRemote || latency.applyToRemoteOutputs,
           let cap = lookup(latency.highLatencyProfileID), cap.qualityRank < result.profile.qualityRank {
            result.profile = cap
            result.cappedByLatency = true
        }
        return result
    }

    private static func networkRule(_ policy: TranscodePolicy, _ context: TranscodeContext, _ lookup: (String?) -> TranscodeProfile?,
                                    _ fallback: TranscodeProfile) -> TranscodeDecision {
        let network = context.network
        let onWifi = network.kind == .wifi || network.kind == .ethernet
        let asCellular = network.kind == .cellular || (network.isMetered && policy.meteredWifiAsCellular && network.kind != .none)
        let away = policy.awayWifiAsCellular && context.atHome == false && onWifi
        if asCellular, let p = lookup(policy.cellularProfileID) { return TranscodeDecision(profile: p, reason: .cellularRule) }
        if !asCellular, away, let p = lookup(policy.cellularProfileID) { return TranscodeDecision(profile: p, reason: .awayRule) }
        if !asCellular, !away, onWifi, let p = lookup(policy.wifiProfileID) { return TranscodeDecision(profile: p, reason: .wifiRule) }
        return TranscodeDecision(profile: fallback, reason: .default)
    }
}

/// An already-compressed file isn't re-encoded: lossy to lossy stacks a second encoder's losses on
/// the first and saves little. It streams as it is, unless the connection is struggling (the decision
/// is capped for latency), when a smaller stream matters more.
public enum LossySourceRule {
    private static let playableLossy: Set<String> = ["mp3", "m4a", "aac", "mp4", "ogg", "oga", "opus", "webm"]

    public static func keepsOriginal(_ decision: TranscodeDecision, container: String?, lossless: Bool) -> Bool {
        let profile = decision.profile
        guard !profile.isPassthrough, profile.codec.lossy, !decision.cappedByLatency, let c = container?.lowercased() else { return false }
        return !lossless && playableLossy.contains(c)
    }

    public static func apply(_ decision: TranscodeDecision, container: String?, lossless: Bool) -> TranscodeDecision {
        keepsOriginal(decision, container: container, lossless: lossless)
            ? TranscodeDecision(profile: .original, reason: .lossySource) : decision
    }
}

/// What to ask the server for, for a profile on this Mac: AVFoundation can't play Ogg (Opus, Vorbis),
/// so those profiles come as MP3 of about the same quality, and a file it can't play comes as MP3 at
/// 320 kbps at most.
public struct StreamChoice: Sendable, Hashable {
    public var format: String?
    public var maxBitRateKbps: Int?
    /// Only Jellyfin honours these two.
    public var maxSampleRateHz: Int?
    public var maxBitDepth: Int?

    public init(format: String? = nil, maxBitRateKbps: Int? = nil, maxSampleRateHz: Int? = nil, maxBitDepth: Int? = nil) {
        self.format = format
        self.maxBitRateKbps = maxBitRateKbps
        self.maxSampleRateHz = maxSampleRateHz
        self.maxBitDepth = maxBitDepth
    }

    private func capped(by profile: TranscodeProfile) -> StreamChoice {
        var out = self
        out.maxSampleRateHz = profile.maxSampleRateHz > 0 ? profile.maxSampleRateHz : nil
        out.maxBitDepth = profile.maxBitDepth > 0 ? profile.maxBitDepth : nil
        return out
    }

    public var isOriginal: Bool { format == nil && maxBitRateKbps == nil && maxSampleRateHz == nil && maxBitDepth == nil }

    /// The file extension and type of the stream, given the file it comes from.
    public func container(source: String?) -> String? { format ?? source }

    /// Opus at 128 kbps sounds about like MP3 at 160: the next rate up.
    static func mp3Equivalent(ofOggKbps kbps: Int) -> Int {
        switch kbps {
        case 0: 320
        case ...64: 96
        case ...96: 128
        case ...128: 160
        case ...160: 192
        case ...192: 256
        default: 320
        }
    }

    /// For this Mac's own player.
    public static func forMac(_ profile: TranscodeProfile, container: String?) -> StreamChoice {
        macChoice(profile, container: container).capped(by: profile)
    }

    private static func macChoice(_ profile: TranscodeProfile, container: String?) -> StreamChoice {
        switch profile.codec {
        case .opus, .vorbis:
            return StreamChoice(format: "mp3", maxBitRateKbps: mp3Equivalent(ofOggKbps: profile.maxBitrateKbps))
        case .original where profile.maxBitrateKbps > 0:
            return StreamChoice(maxBitRateKbps: profile.maxBitrateKbps)
        case .original:
            return AppleFormats.plays(container) ? StreamChoice()
                : StreamChoice(format: AppleFormats.fallbackFormat, maxBitRateKbps: AppleFormats.fallbackBitrateKbps)
        case .flac:
            return StreamChoice(format: "flac")
        case .aac:
            return StreamChoice(format: "aac", maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        case .mp3:
            return StreamChoice(format: "mp3", maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        }
    }

    /// For something that fetches the audio itself and plays what it says it plays.
    public static func forRenderer(_ profile: TranscodeProfile) -> StreamChoice {
        rendererChoice(profile).capped(by: profile)
    }

    private static func rendererChoice(_ profile: TranscodeProfile) -> StreamChoice {
        switch profile.codec {
        case .original: StreamChoice(maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        case .flac: StreamChoice(format: "flac")
        case .opus: StreamChoice(format: "opus", maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        case .vorbis: StreamChoice(format: "ogg", maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        case .aac: StreamChoice(format: "aac", maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        case .mp3: StreamChoice(format: "mp3", maxBitRateKbps: profile.maxBitrateKbps > 0 ? profile.maxBitrateKbps : nil)
        }
    }
}

/// The MIME type of a file or stream, by its format.
public enum AudioMime {
    public static func of(_ container: String?) -> String {
        switch container?.lowercased() {
        case "flac": "audio/flac"
        case "mp3": "audio/mpeg"
        case "m4a", "aac", "mp4", "alac": "audio/mp4"
        case "ogg", "oga", "vorbis", "opus": "audio/ogg"
        case "wav": "audio/wav"
        case "aiff", "aif": "audio/aiff"
        case "webm": "audio/webm"
        case nil: "audio/flac"
        default: "audio/mpeg"
        }
    }
}

/// Which audio formats a speaker says it plays (its GetProtocolInfo). Unknown: all of them.
public struct RendererFormats: Sendable, Equatable {
    public var flac = true, mp3 = true, aac = true, opus = true, vorbis = true, wav = true
    public var unknown = true

    public init() {}

    public static let unknownFormats = RendererFormats()

    public init(mimeTypes: Set<String>) {
        guard !mimeTypes.isEmpty else { return }
        let m = mimeTypes.map { $0.lowercased() }
        func any(_ needles: String...) -> Bool { m.contains { mime in needles.contains { mime.contains($0) } } }
        flac = any("flac")
        mp3 = any("audio/mpeg", "audio/mp3")
        aac = any("audio/mp4", "audio/aac", "audio/x-m4a", "audio/3gpp")
        opus = any("opus")
        vorbis = m.contains { $0.contains("vorbis") || ($0.hasPrefix("audio/ogg") && !$0.contains("opus")) }
        wav = any("wav", "audio/l16")
        unknown = false
    }

    /// The types in a Sink list: "http-get:*:audio/mpeg:*,http-get:*:audio/flac:*".
    public static func parse(sink: String?) -> RendererFormats {
        let types = (sink ?? "").split(separator: ",").compactMap { entry -> String? in
            let parts = entry.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count >= 3 else { return nil }
            return parts[2].trimmingCharacters(in: .whitespaces)
        }
        return RendererFormats(mimeTypes: Set(types))
    }

    public func supports(_ mime: String?) -> Bool {
        guard !unknown, let m = mime?.lowercased() else { return true }
        if m.contains("flac") { return flac }
        if m == "audio/mpeg" || m == "audio/mp3" { return mp3 }
        if m == "audio/mp4" || m == "audio/aac" || m == "audio/x-m4a" { return aac }
        if m.contains("opus") { return opus }
        if m == "audio/ogg" || m.contains("vorbis") { return vorbis }
        if m.contains("wav") || m.hasPrefix("audio/l16") { return wav }
        return true
    }

    /// Codecs in the order this speaker prefers them.
    public var preferredCodecs: [AudioCodec] {
        if unknown { return [.flac, .mp3, .aac, .opus, .vorbis] }
        var out: [AudioCodec] = []
        if flac { out.append(.flac) }
        if mp3 { out.append(.mp3) }
        if aac { out.append(.aac) }
        if opus { out.append(.opus) }
        if vorbis { out.append(.vorbis) }
        return out.isEmpty ? [.mp3] : out // every speaker met plays MP3, even when it says otherwise
    }
}

/// The user's profile if the speaker takes it, else the first codec it does take. Nothing matching:
/// the profile anyway, so the speaker at least gets a chance.
public enum RendererCodecFallback {
    static let aac = TranscodeProfile(id: "dlna.aac", name: "AAC 256 kbps", codec: .aac, maxBitrateKbps: 256, builtIn: true)
    static let vorbis = TranscodeProfile(id: "dlna.vorbis", name: "Vorbis 256 kbps", codec: .vorbis, maxBitrateKbps: 256, builtIn: true)

    public static func mime(of profile: TranscodeProfile, container: String?) -> String {
        switch profile.codec {
        case .original: AudioMime.of(container)
        case .flac: "audio/flac"
        case .mp3: "audio/mpeg"
        case .aac: "audio/mp4"
        case .opus, .vorbis: "audio/ogg"
        }
    }

    static func profile(for codec: AudioCodec, container: String?) -> TranscodeProfile? {
        let c = container?.lowercased()
        switch codec {
        case .flac: return c == "flac" ? .original : .losslessCapped
        case .mp3: return c == "mp3" ? .original : .castSafe
        case .aac: return c == "m4a" || c == "aac" || c == "mp4" ? .original : aac
        case .opus: return c == "opus" ? .original : .high
        case .vorbis: return c == "ogg" || c == "oga" ? .original : vorbis
        case .original: return nil
        }
    }

    public static func choose(_ preferred: TranscodeProfile, container: String?, formats: RendererFormats)
        -> (profile: TranscodeProfile, mime: String, substituted: Bool) {
        let first = mime(of: preferred, container: container)
        if formats.supports(first) { return (preferred, first, false) }
        for codec in formats.preferredCodecs {
            guard let candidate = profile(for: codec, container: container), candidate.id != preferred.id else { continue }
            let m = mime(of: candidate, container: container)
            if formats.supports(m) { return (candidate, m, true) }
        }
        return (preferred, first, false)
    }
}

/// Which upcoming songs to have whole on this Mac, so a dropped connection doesn't stop the music at
/// the next song. In minutes of music rather than songs: 20 minutes (2 to 10 songs), or 8 (1 to 3)
/// on a metered connection, when the setting allows it. The Android app's PrefetchPlan.
public enum PrefetchPlan {
    public struct Upcoming: Sendable, Equatable {
        public var trackID: String
        public var durationMs: Int
        public var onDevice: Bool
        public var fetchable: Bool

        public init(trackID: String, durationMs: Int, onDevice: Bool = false, fetchable: Bool = true) {
            self.trackID = trackID
            self.durationMs = durationMs
            self.onDevice = onDevice
            self.fetchable = fetchable
        }
    }

    public struct Budget: Sendable, Equatable {
        public var aheadMs: Int
        public var minSongs: Int
        public var maxSongs: Int
    }

    public static let unmetered = Budget(aheadMs: 20 * 60_000, minSongs: 2, maxSongs: 10)
    public static let metered = Budget(aheadMs: 8 * 60_000, minSongs: 1, maxSongs: 3)
    private static let unknownDurationMs = 4 * 60_000

    public static func budget(_ network: NetworkState, enabled: Bool, onMetered: Bool) -> Budget? {
        guard enabled, network.isOnline else { return nil }
        if !network.isMetered { return unmetered }
        return onMetered ? metered : nil
    }

    public static func pick(_ upcoming: [Upcoming], _ budget: Budget?) -> [String] {
        guard let budget else { return [] }
        var out: [String] = []
        var ahead = 0
        for (i, song) in upcoming.enumerated() {
            if i >= budget.maxSongs { break }
            if ahead >= budget.aheadMs && i >= budget.minSongs { break }
            if !song.onDevice && song.fetchable { out.append(song.trackID) }
            ahead += song.durationMs > 0 ? song.durationMs : unknownDurationMs
        }
        return out
    }
}

/// Whether the connection is degraded, from rebuffers while music was playing: too many within a
/// window, or a single long one. It recovers after a while without any, on [reset], or by hand. The
/// Android app's LatencyMonitor.
public struct LatencyMonitor: Sendable {
    public var settings = AdaptiveLatencySettings()
    public private(set) var degraded = false
    private var stallStarts: [Int] = []
    private var ongoingStart: Int?
    private var lastActivity = 0

    public init() {}

    public mutating func stallStarted(nowMs: Int) {
        guard settings.enabled else { return }
        ongoingStart = nowMs
        lastActivity = nowMs
        stallStarts.append(nowMs)
        stallStarts.removeAll { nowMs - $0 > settings.windowSeconds * 1000 }
        if stallStarts.count >= max(settings.stallThreshold, 1) { degraded = true }
    }

    public mutating func stallEnded(nowMs: Int) {
        guard let start = ongoingStart else { return }
        ongoingStart = nil
        lastActivity = nowMs
        if settings.enabled && nowMs - start >= settings.longStallMs { degraded = true }
    }

    /// A few times a minute: applies the long-stall and recovery rules.
    public mutating func tick(nowMs: Int) {
        if let start = ongoingStart {
            if settings.enabled && nowMs - start >= settings.longStallMs { degraded = true }
            return
        }
        if degraded, settings.recoverAfterSeconds > 0, nowMs - lastActivity >= settings.recoverAfterSeconds * 1000 { reset() }
    }

    /// Clears everything: the network changed, or the user pressed Restore quality.
    public mutating func reset() {
        stallStarts = []
        ongoingStart = nil
        degraded = false
    }
}

/// Whether this Mac is on the home network: the active server answers at its LAN address. Nil when it
/// can't be told, which leaves the rules as they were. A stranger's device at the same private
/// address wouldn't send the server's own error document.
public enum HomeNetwork {
    /// The receiver address, or the base URL when its host is a private LAN address.
    public static func lanBase(receiver: String?, baseURL: URL) -> URL? {
        if let raw = receiver?.trimmingCharacters(in: .whitespaces), !raw.isEmpty {
            var s = raw.contains("://") ? raw : "http://" + raw
            while s.hasSuffix("/") { s.removeLast() }
            return URL(string: s)
        }
        guard let host = baseURL.host, isPrivateIPv4(host) else { return nil }
        return baseURL
    }

    static func isPrivateIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        return parts[0] == 10 || (parts[0] == 172 && (16...31).contains(parts[1])) || (parts[0] == 192 && parts[1] == 168)
    }

    public static func check(kind: NetworkKind, lan: URL?, subsonic: Bool, transport: HTTPTransport) async -> Bool? {
        switch kind {
        case .none: return nil
        case .cellular: return false
        default: break
        }
        guard let lan else { return nil }
        let url: URL
        if subsonic {
            var parts = URLComponents(url: lan.appendingPathComponent("rest/stream"), resolvingAgainstBaseURL: false)
            parts?.queryItems = [URLQueryItem(name: "v", value: "1.16.1"), URLQueryItem(name: "c", value: "Polyphony"), URLQueryItem(name: "f", value: "xml")]
            guard let built = parts?.url else { return nil }
            url = built
        } else {
            url = lan
        }
        guard let response = try? await transport.send(HTTPRequest(url: url)) else { return false }
        if subsonic { return String(decoding: response.body.prefix(4096), as: UTF8.self).contains("subsonic-response") }
        return response.status < 500
    }
}
