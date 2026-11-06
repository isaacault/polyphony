import Foundation
import Observation

/// The quality rules as they stand: the user's policy and profiles, plus what the Mac knows right now
/// (the network, whether it's home, whether the connection is struggling). The app feeds it and asks
/// it what each song should stream as; the rules themselves are [TranscodeResolver]'s.
@MainActor
@Observable
public final class StreamingRules {
    /// What's kept between launches.
    public struct Stored: Sendable, Codable, Equatable {
        public var policy = TranscodePolicy()
        public var profiles: [TranscodeProfile] = []
        /// A profile chosen for one speaker or Cast device, by its target key.
        public var deviceProfiles: [String: String] = [:]
        /// Remote outputs seen, by target key: their names, for Settings.
        public var knownDevices: [String: String] = [:]
        public init() {}
    }

    public private(set) var stored: Stored
    public private(set) var network = NetworkState.unknown
    public private(set) var atHome: Bool?
    public private(set) var latencyDegraded = false
    @ObservationIgnored private var monitor = LatencyMonitor()

    /// The stored rules changed: write them to disk.
    @ObservationIgnored public var persist: @MainActor (Stored) -> Void = { _ in }
    /// The local decision changed by itself (a network change, a latency cap), or the rules did.
    @ObservationIgnored public var onChange: @MainActor () -> Void = {}

    public init(stored: Stored = Stored()) {
        self.stored = stored
        monitor.settings = stored.policy.adaptiveLatency
    }

    public var policy: TranscodePolicy { stored.policy }
    public var allProfiles: [TranscodeProfile] { TranscodeProfile.builtIns + stored.profiles }

    public func profile(_ id: String?) -> TranscodeProfile? {
        guard let id else { return nil }
        return allProfiles.first { $0.id == id }
    }

    public func decision(for target: OutputTarget = .local) -> TranscodeDecision {
        TranscodeResolver.resolve(stored.policy, profiles: stored.profiles,
                                  TranscodeContext(network: network, target: target, latencyDegraded: latencyDegraded,
                                                   deviceProfileID: target.isRemote ? stored.deviceProfiles[target.key] : nil,
                                                   atHome: atHome))
    }

    /// What this song should stream as on this Mac: an already-compressed file stays as it is, if
    /// this Mac can play it.
    public func localDecision(container: String?, lossless: Bool) -> TranscodeDecision {
        let d = decision()
        return AppleFormats.plays(container) ? LossySourceRule.apply(d, container: container, lossless: lossless) : d
    }

    // MARK: The rules

    public func update(_ change: (inout TranscodePolicy) -> Void) {
        let before = decision()
        change(&stored.policy)
        monitor.settings = stored.policy.adaptiveLatency
        if !monitor.settings.enabled { monitor.reset(); latencyDegraded = false }
        save()
        changed(from: before)
    }

    /// Adds a profile, or replaces the one with its id.
    public func save(profile: TranscodeProfile) {
        let before = decision()
        if let i = stored.profiles.firstIndex(where: { $0.id == profile.id }) {
            stored.profiles[i] = profile
        } else {
            stored.profiles.append(profile)
        }
        save()
        changed(from: before)
    }

    /// Removes a profile; rules and devices that used it fall back to their defaults.
    public func delete(profile id: String) {
        let before = decision()
        stored.profiles.removeAll { $0.id == id }
        if stored.policy.defaultProfileID == id { stored.policy.defaultProfileID = TranscodeProfile.originalID }
        if stored.policy.wifiProfileID == id { stored.policy.wifiProfileID = nil }
        if stored.policy.cellularProfileID == id { stored.policy.cellularProfileID = nil }
        if stored.policy.castDefaultProfileID == id { stored.policy.castDefaultProfileID = nil }
        if stored.policy.adaptiveLatency.highLatencyProfileID == id { stored.policy.adaptiveLatency.highLatencyProfileID = nil }
        stored.deviceProfiles = stored.deviceProfiles.filter { $0.value != id }
        monitor.settings = stored.policy.adaptiveLatency
        save()
        changed(from: before)
    }

    public func setProfile(_ id: String?, forDevice key: String) {
        stored.deviceProfiles[key] = id
        save()
    }

    public func remember(_ target: OutputTarget) {
        guard target.isRemote, stored.knownDevices[target.key] != target.displayName else { return }
        stored.knownDevices[target.key] = target.displayName
        save()
    }

    public func forget(device key: String) {
        stored.knownDevices[key] = nil
        stored.deviceProfiles[key] = nil
        save()
    }

    private func save() { persist(stored) }

    private func changed(from before: TranscodeDecision) {
        if decision() != before { onChange() }
    }

    // MARK: What's happening

    public func setNetwork(_ state: NetworkState) {
        guard state != network else { return }
        let before = decision()
        // A new network starts with a clean slate: its stalls say nothing about this one.
        if state.kind != network.kind || state.isMetered != network.isMetered { resetLatency() }
        network = state
        changed(from: before)
    }

    public func setAtHome(_ home: Bool?) {
        guard home != atHome else { return }
        let before = decision()
        atHome = home
        changed(from: before)
    }

    public func stall(started: Bool, nowMs: Int) {
        let before = decision()
        if started { monitor.stallStarted(nowMs: nowMs) } else { monitor.stallEnded(nowMs: nowMs) }
        latencyDegraded = monitor.degraded
        changed(from: before)
    }

    /// A few times a minute.
    public func tick(nowMs: Int) {
        let before = decision()
        monitor.tick(nowMs: nowMs)
        latencyDegraded = monitor.degraded
        changed(from: before)
    }

    /// Settings > Streaming > Restore quality.
    public func restoreQuality() {
        let before = decision()
        resetLatency()
        changed(from: before)
    }

    private func resetLatency() {
        monitor.reset()
        latencyDegraded = false
    }

    /// The old single choice (0: original, else MP3 at this bitrate) as rules.
    public static func migrated(bitrateKbps: Int) -> Stored {
        var stored = Stored()
        guard bitrateKbps > 0 else { return stored }
        let id = "custom.mp3.\(bitrateKbps)"
        stored.profiles = [TranscodeProfile(id: id, name: "MP3 \(bitrateKbps) kbps", codec: .mp3, maxBitrateKbps: bitrateKbps)]
        stored.policy.defaultProfileID = id
        return stored
    }
}
