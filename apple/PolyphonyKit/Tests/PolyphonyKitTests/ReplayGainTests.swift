import Foundation
import Testing
@testable import PolyphonyKit

/// Android's ReplayGainTest, case for case.
struct ReplayGainTests {
    private func track(_ trackDb: Double? = nil, album albumDb: Double? = nil, trackPeak: Double? = nil, albumPeak: Double? = nil, albumID: String = "a") -> Track {
        Track(id: "t", title: "T", albumID: albumID,
              replayGain: ReplayGain.Tags(trackGainDb: trackDb, albumGainDb: albumDb, trackPeak: trackPeak, albumPeak: albumPeak))
    }

    private func v(_ t: Track?, _ mode: ReplayGain.Mode, preamp: Double = 0, clip: Bool = true, same: Bool = false) -> Float {
        ReplayGain.volume(for: t, ReplayGain.Settings(mode: mode, preampDb: preamp, preventClipping: clip), sameAlbumAsNeighbour: same)
    }

    private func near(_ a: Float, _ b: Float, _ tolerance: Float = 0.002) -> Bool { abs(a - b) <= tolerance }

    @Test func offAndMissingTagsGiveUnity() {
        #expect(v(track(-6), .off) == 1 && v(track(), .track) == 1 && v(nil, .track) == 1)
        #expect(v(Track(id: "x", title: "x"), .track) == 1)
    }

    @Test func negativeGainAttenuates() { #expect(near(v(track(-6), .track), 0.501)) }

    @Test func positiveGainClampsAtUnity() { #expect(v(track(4), .track, clip: false) == 1) }

    @Test func preampIsAdded() { #expect(near(v(track(-6), .track, preamp: 3), 0.708)) }

    @Test func clippingPreventionRespectsPeak() {
        #expect(near(v(track(-1, trackPeak: 0.95), .track), 0.891))
        #expect(near(v(track(-1, trackPeak: 2.0), .track), 0.5))
    }

    @Test func autoPicksAlbumGainOnlyWhenTheNeighbourSharesTheAlbum() {
        let t = track(-6, album: -12)
        #expect(near(v(t, .auto, same: true), 0.251) && near(v(t, .auto, same: false), 0.501))
        #expect(near(v(t, .album), 0.251))
    }

    @Test func sameAlbum() {
        #expect(ReplayGain.sameAlbum(track(albumID: "x"), track(albumID: "x")))
        #expect(!ReplayGain.sameAlbum(track(albumID: "x"), track(albumID: "y")) && !ReplayGain.sameAlbum(track(albumID: "x"), nil))
    }

    @Test func navidromesTagsAreRead() throws {
        let json = #"{"id":"1","title":"Song","albumId":"a","duration":200,"replayGain":{"trackGain":-7.5,"albumGain":-8.1,"trackPeak":0.98,"albumPeak":1.0}}"#
        let t = try JSONDecoder().decode(ChildDTO.self, from: Data(json.utf8)).model
        #expect(t.replayGain == ReplayGain.Tags(trackGainDb: -7.5, albumGainDb: -8.1, trackPeak: 0.98, albumPeak: 1.0))
        let none = #"{"id":"2","title":"Song","duration":200,"replayGain":{}}"#
        #expect(try JSONDecoder().decode(ChildDTO.self, from: Data(none.utf8)).model.replayGain == nil)
    }
}

private final class GainSpy: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?
    var volume: Float = 1
    var gains: [Float] = []
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {}
    func preload(_ next: AudioSource?) {}
    func play() {}
    func pause() {}
    func seek(to time: TimeInterval) {}
    func stop() {}
    func setGain(_ gain: Float) { gains.append(gain) }
    var rates: [Float] = []
    func setRate(_ rate: Float) { rates.append(rate) }
}

@MainActor
struct PlayerGainTests {
    @Test func eachSongGetsItsLevelAndAnAlbumPlaysAtAlbumGain() {
        let spy = GainSpy()
        let p = PlayerController(engine: spy)
        p.replayGain = ReplayGain.Settings(mode: .auto)
        let tags = ReplayGain.Tags(trackGainDb: -6, albumGainDb: -12)
        p.play([Track(id: "a", title: "a", albumID: "x", replayGain: tags), Track(id: "b", title: "b", albumID: "x", replayGain: tags)])
        #expect(abs((spy.gains.last ?? 0) - 0.251) < 0.002, "an album playing through: album gain")
        p.play([Track(id: "a", title: "a", albumID: "x", replayGain: tags), Track(id: "c", title: "c", albumID: "y")])
        #expect(abs((spy.gains.last ?? 0) - 0.501) < 0.002, "a mix: track gain")
        p.replayGain = ReplayGain.Settings(mode: .off)
        #expect(spy.gains.last == 1, "turning it off levels nothing, at once")
    }
}

@MainActor
struct SpeedTests {
    @Test func theSpeedGoesToTheEngineAndCarriesOverToANewOne() {
        let mac = GainSpy()
        let p = PlayerController(engine: mac)
        p.speed = 1.5
        #expect(mac.rates == [1.5])
        let speaker = GainSpy()
        p.switchOutput(to: speaker)
        #expect(speaker.rates == [1.5])
    }
}
