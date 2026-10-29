import Testing
@testable import PolyphonyKit

struct QualityTests {
    private func track(_ id: String, _ container: String? = "flac", bits: Int? = 16, rate: Int? = 44_100) -> Track {
        Track(id: id, title: id, bitDepth: bits, sampleRateHz: rate, container: container)
    }

    @Test func badges() {
        #expect(AudioQuality.badge(for: track("cd")) == "FLAC")
        #expect(AudioQuality.badge(for: track("24", bits: 24)) == "HI-RES")
        #expect(AudioQuality.badge(for: track("96k", bits: 16, rate: 96_000)) == "HI-RES")
        #expect(AudioQuality.badge(for: track("mp3", "mp3")) == nil)
    }

    @Test func onAHiResAlbumTheCDTrackSays16Bit() {
        // We All Speak in Poems before its first track was replaced: ten 24-bit tracks, one 16-bit.
        let tracks = [track("Speaker")] + (2...11).map { track("\($0)", bits: 24) }
        let album = AudioQuality.albumBadge(tracks)
        #expect(album == "HI-RES")
        #expect(AudioQuality.trackChip(tracks[0], albumBadge: album) == "16-BIT")
        #expect(AudioQuality.trackChip(tracks[1], albumBadge: album) == nil)
        #expect(AudioQuality.trackChip(track("mp3", "mp3"), albumBadge: album) == nil)
    }

    @Test func onACDAlbumAHiResTrackSaysHiRes() {
        let tracks = [track("a"), track("b"), track("c", bits: 24)]
        #expect(AudioQuality.albumBadge(tracks) == "FLAC")
        #expect(AudioQuality.trackChip(tracks[2], albumBadge: "FLAC") == "HI-RES")
    }
}
