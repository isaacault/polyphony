import Foundation

/// Levelling loudness between songs from their ReplayGain tags (ported from the Android app). The
/// player's volume tops out at 1, so a positive gain is clamped.
public enum ReplayGain {
    public struct Tags: Sendable, Codable, Hashable {
        public var trackGainDb: Double?
        public var albumGainDb: Double?
        public var trackPeak: Double?
        public var albumPeak: Double?

        public init(trackGainDb: Double? = nil, albumGainDb: Double? = nil, trackPeak: Double? = nil, albumPeak: Double? = nil) {
            self.trackGainDb = trackGainDb
            self.albumGainDb = albumGainDb
            self.trackPeak = trackPeak
            self.albumPeak = albumPeak
        }
    }

    public enum Mode: String, Sendable, Codable, CaseIterable {
        case off, track, album
        /// Album gain while an album plays through (its quiet songs stay quiet), track gain on a mix.
        case auto
    }

    public struct Settings: Sendable, Codable, Equatable {
        public var mode: Mode
        public var preampDb: Double
        public var preventClipping: Bool

        public init(mode: Mode = .off, preampDb: Double = 0, preventClipping: Bool = true) {
            self.mode = mode
            self.preampDb = preampDb
            self.preventClipping = preventClipping
        }
    }

    public static func volume(for track: Track?, _ settings: Settings, sameAlbumAsNeighbour: Bool) -> Float {
        guard let tags = track?.replayGain, settings.mode != .off else { return 1 }
        let useAlbum = settings.mode == .album || (settings.mode == .auto && sameAlbumAsNeighbour)
        let gain = useAlbum ? (tags.albumGainDb ?? tags.trackGainDb) : (tags.trackGainDb ?? tags.albumGainDb)
        let peak = useAlbum ? (tags.albumPeak ?? tags.trackPeak) : (tags.trackPeak ?? tags.albumPeak)
        guard let gain else { return 1 }
        var linear = pow(10, (gain + settings.preampDb) / 20)
        if settings.preventClipping, let peak, peak > 0 { linear = min(linear, 1 / peak) }
        return Float(min(max(linear, 0), 1))
    }

    public static func sameAlbum(_ a: Track?, _ b: Track?) -> Bool {
        guard let a, let b, let album = a.albumID else { return false }
        return album == b.albumID
    }
}
