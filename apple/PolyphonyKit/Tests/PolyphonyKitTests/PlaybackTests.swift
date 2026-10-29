import Foundation
import Testing
@testable import PolyphonyKit

private func t(_ id: String, _ seconds: TimeInterval = 200) -> Track { Track(id: id, title: id, duration: seconds) }
private func ids(_ items: [PlayQueue.Item]) -> [String] { items.map { $0.entry.libraryTrack?.id ?? "?" } }

struct PlayQueueTests {
    @Test func playsInOrderAndStopsAtTheEnd() {
        var q = PlayQueue([.track(t("a")), .track(t("b"))])
        #expect(q.current?.entry.libraryTrack?.id == "a")
        let moved1 = q.advanceOnEnd()
        #expect(moved1 && q.current?.entry.libraryTrack?.id == "b")
        let movedAtEnd = q.advanceOnEnd()
        #expect(!movedAtEnd)
    }

    @Test func repeatAllWrapsAndRepeatOneStays() {
        var q = PlayQueue([.track(t("a")), .track(t("b"))], startAt: 1)
        q.repeatMode = .all
        let moved2 = q.advanceOnEnd()
        #expect(moved2 && q.current?.entry.libraryTrack?.id == "a")
        q.repeatMode = .one
        let moved3 = q.advanceOnEnd()
        #expect(moved3 && q.current?.entry.libraryTrack?.id == "a", "repeat one replays")
        let moved4 = q.next()
        #expect(moved4 && q.current?.entry.libraryTrack?.id == "b", "but Next still moves on")
    }

    @Test func shuffleKeepsWhatsPlayingFirstAndUnshuffleGoesBack() {
        var q = PlayQueue((1...20).map { .track(t("\($0)")) }, startAt: 4)
        q.setShuffle(true)
        #expect(q.current?.entry.libraryTrack?.id == "5" && q.history.isEmpty && q.upNext.count == 19)
        #expect(Set(ids(q.upNext)) == Set((1...20).map(String.init)).subtracting(["5"]))
        q.setShuffle(false)
        #expect(q.current?.entry.libraryTrack?.id == "5" && ids(q.upNext).first == "6")
    }

    @Test func playNextGoesRightAfterAndAppendAtTheEnd() {
        var q = PlayQueue([.track(t("a")), .track(t("b"))])
        q.playNext([.track(t("x"))])
        q.append([.track(t("z"))])
        #expect(ids(q.upNext) == ["x", "b", "z"])
    }

    @Test func removingWhatsPlayingMovesToWhatWasNext() {
        var q = PlayQueue([.track(t("a")), .track(t("b")), .track(t("c"))], startAt: 1)
        q.remove(q.current!.id)
        #expect(q.current?.entry.libraryTrack?.id == "c")
        q.remove(q.current!.id)
        #expect(q.current?.entry.libraryTrack?.id == "a", "at the end it falls back")
        q.remove(q.current!.id)
        #expect(q.current == nil && q.isEmpty)
    }

    @Test func movingAndClearingUpNext() {
        var q = PlayQueue([.track(t("a")), .track(t("b")), .track(t("c")), .track(t("d"))])
        q.moveUpNext(from: 2, to: 0)
        #expect(ids(q.upNext) == ["d", "b", "c"])
        q.clearUpNext()
        #expect(q.upNext.isEmpty && q.current?.entry.libraryTrack?.id == "a" && q.items.count == 1)
    }

    @Test func theSameSongTwiceIsTwoRows() {
        let q = PlayQueue([.track(t("a")), .track(t("a"))])
        #expect(q.items[0].id != q.items[1].id)
    }

    @Test func aPlayCountsAtHalfOrFourMinutes() {
        #expect(!ScrobbleRule.counts(played: 99, duration: 200) && ScrobbleRule.counts(played: 100, duration: 200))
        #expect(ScrobbleRule.counts(played: 240, duration: 3600), "four minutes into a long mix")
        #expect(!ScrobbleRule.counts(played: 25, duration: 25), "too short to count")
    }
}

@MainActor
private final class FakeEngine: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?
    var volume: Float = 1
    var loaded: [AudioSource] = []
    var preloaded: AudioSource??
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        loaded.append(source)
        if autoplay { onEvent?(.started) }
    }
    func preload(_ next: AudioSource?) { preloaded = .some(next) }
    func play() { onEvent?(.started) }
    func pause() { onEvent?(.paused) }
    func seek(to time: TimeInterval) {}
    func stop() {}
}

@MainActor
private final class Recorder: NowPlayingPublisher {
    var last: NowPlayingInfo??
    func publish(_ info: NowPlayingInfo?) { last = .some(info) }
}

@MainActor
private func settle() async {
    for _ in 0..<20 { await Task.yield() }
}

@MainActor
struct PlayerControllerTests {
    private func player(_ engine: FakeEngine, _ recorder: Recorder? = nil) -> PlayerController {
        let p = PlayerController(engine: engine, nowPlaying: recorder)
        p.resolve = { entry in AudioSource(url: URL(string: "https://x/\(entry.libraryTrack?.id ?? "?")")!) }
        return p
    }

    @Test func playingLoadsTheTrackAndPreloadsTheNext() async {
        let engine = FakeEngine()
        let p = player(engine)
        p.play([t("a"), t("b")])
        await settle()
        #expect(engine.loaded.map(\.url.lastPathComponent) == ["a"] && p.isPlaying)
        #expect(engine.preloaded??.url.lastPathComponent == "b")
    }

    @Test func theEndOfASongPlaysTheNext() async {
        let engine = FakeEngine()
        let p = player(engine)
        p.play([t("a"), t("b")])
        await settle()
        engine.onEvent?(.ended)
        await settle()
        #expect(engine.loaded.map(\.url.lastPathComponent) == ["a", "b"])
        engine.onEvent?(.ended)
        await settle()
        #expect(!p.isPlaying && engine.loaded.count == 2, "the queue ended")
    }

    @Test func previousRestartsAfterThreeSeconds() async {
        let engine = FakeEngine()
        let p = player(engine)
        p.play([t("a"), t("b")], startAt: 1)
        await settle()
        engine.onEvent?(.position(10, duration: 200))
        p.previous()
        await settle()
        #expect(engine.loaded.count == 1 && p.position == 0, "restarted b")
        p.previous()
        await settle()
        #expect(engine.loaded.map(\.url.lastPathComponent) == ["b", "a"])
    }

    @Test func scrobblesNowPlayingThenOnceAtHalfway() async {
        let engine = FakeEngine()
        let p = player(engine)
        var scrobbles: [(String, Bool)] = []
        p.scrobble = { track, submission in scrobbles.append((track.id, submission)) }
        p.play([t("a", 200)])
        await settle()
        for s in stride(from: 1.0, through: 120, by: 1) { engine.onEvent?(.position(s, duration: 200)) }
        #expect(scrobbles.map(\.0) == ["a", "a"] && scrobbles.map(\.1) == [false, true])
    }

    @Test func seekingAheadIsntListening() async {
        let engine = FakeEngine()
        let p = player(engine)
        var counted = false
        p.scrobble = { _, submission in if submission { counted = true } }
        p.play([t("a", 200)])
        await settle()
        engine.onEvent?(.position(1, duration: 200))
        engine.onEvent?(.position(150, duration: 200))
        #expect(!counted)
    }

    @Test func nowPlayingFollowsTheSong() async {
        let engine = FakeEngine()
        let recorder = Recorder()
        let p = player(engine, recorder)
        p.play([t("a")])
        await settle()
        #expect(recorder.last??.title == "a" && recorder.last??.isPlaying == true)
        p.stop()
        #expect(recorder.last! == nil)
    }

    @Test func aSongThatCantBeResolvedSaysSo() async {
        let engine = FakeEngine()
        let p = PlayerController(engine: engine)
        p.resolve = { _ in nil }
        p.play([t("a")])
        await settle()
        #expect(engine.loaded.isEmpty && p.error == "Can't play a.")
    }
}

struct QueueSnapshotTests {
    @Test func aSnapshotKeepsThePlayOrderAndThePlace() {
        var q = PlayQueue((1...5).map { .track(Track(id: "\($0)", title: "\($0)", duration: 200)) }, startAt: 2, title: "Album")
        q.repeatMode = .all
        let snap = q.snapshot(position: 42)!
        #expect(snap.tracks.map(\.id) == ["1", "2", "3", "4", "5"] && snap.index == 2 && snap.position == 42)
        let back = PlayQueue(snapshot: snap)
        #expect(back.current?.entry.libraryTrack?.id == "3" && back.repeatMode == .all && back.title == "Album")
    }

    @Test func playNowEntriesArentKept() {
        let stream = PlayNowStream(id: "s", status: "ready", playable: true, percent: 100, title: "Loaded", artist: nil,
                                   durationMs: 1000, format: nil, error: nil)
        let catalog = CatalogTrack(key: "k", title: "Loaded", artist: nil, durationMs: 1000, albumTitle: nil,
                                   albumReleaseGroupID: nil, availability: .requestable, recordingID: "r", artworkURL: nil)
        let q = PlayQueue([.track(Track(id: "a", title: "a")), .playNow(stream, catalog), .track(Track(id: "b", title: "b"))], startAt: 1)
        let snap = q.snapshot(position: 30)!
        #expect(snap.tracks.map(\.id) == ["a", "b"] && snap.index == 0 && snap.position == 0,
                "on a Play now song: back to the library track before it, from the start")
    }

    @Test func aSnapshotSurvivesJSON() throws {
        let q = PlayQueue([.track(Track(id: "a", title: "A", albumName: "X", bitDepth: 24, container: "flac"))])
        let snap = q.snapshot(position: 5)!
        #expect(try JSONDecoder().decode(PlayQueue.Snapshot.self, from: JSONEncoder().encode(snap)) == snap)
    }
}

@MainActor
struct PlayerRestoreTests {
    @Test func restoringLoadsPausedAtTheSavedPlace() async {
        let engine = RestoreEngine()
        let p = PlayerController(engine: engine)
        p.resolve = { entry in AudioSource(url: URL(string: "https://x/\(entry.libraryTrack!.id)")!) }
        let snap = PlayQueue([.track(Track(id: "a", title: "a", duration: 200)), .track(Track(id: "b", title: "b"))])
            .snapshot(position: 77)!
        p.restore(snap)
        for _ in 0..<20 { await Task.yield() }
        #expect(engine.loads.map(\.0.url.lastPathComponent) == ["a"] && engine.loads.first?.1 == 77 && engine.loads.first?.2 == false)
        #expect(!p.isPlaying && p.position == 77)
    }

    @Test func aNewSongAndAPauseAreKept() async {
        let engine = RestoreEngine()
        let p = PlayerController(engine: engine)
        p.resolve = { _ in AudioSource(url: URL(string: "https://x/y")!) }
        var kept: [PlayQueue.Snapshot?] = []
        p.persist = { kept.append($0) }
        p.play([Track(id: "a", title: "a"), Track(id: "b", title: "b")])
        engine.onEvent?(.paused)
        p.stop()
        #expect(kept.count == 3 && kept[0]?.index == 0 && kept[2] == nil)
    }
}

@MainActor
private final class RestoreEngine: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?
    var volume: Float = 1
    var loads: [(AudioSource, TimeInterval, Bool)] = []
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) { loads.append((source, startAt, autoplay)) }
    func preload(_ next: AudioSource?) {}
    func play() {}
    func pause() {}
    func seek(to time: TimeInterval) {}
    func stop() {}
}

@MainActor
struct SleepTimerTests {
    @Test func aTimedSleepPausesWhenItsDue() async {
        var clock = Date(timeIntervalSince1970: 0)
        var paused = false
        let spy = PauseSpy()
        spy.onPause = { paused = true }
        let p = PlayerController(engine: spy)
        p.now = { clock }
        p.resolve = { _ in AudioSource(url: URL(string: "https://x/y")!) }
        p.play([Track(id: "a", title: "a", duration: 3600)])
        p.setSleepTimer(.minutes(15))
        clock = clock.addingTimeInterval(14 * 60)
        spy.onEvent?(.position(10, duration: 3600))
        #expect(!paused)
        clock = clock.addingTimeInterval(61)
        spy.onEvent?(.position(11, duration: 3600))
        #expect(paused && p.sleepAt == nil)
    }

    @Test func endOfSongStopsAfterThisOne() async {
        let spy = PauseSpy()
        let p = PlayerController(engine: spy)
        p.resolve = { entry in AudioSource(url: URL(string: "https://x/\(entry.libraryTrack!.id)")!) }
        p.play([Track(id: "a", title: "a"), Track(id: "b", title: "b")])
        for _ in 0..<20 { await Task.yield() }
        p.setSleepTimer(.endOfSong)
        spy.onEvent?(.ended)
        for _ in 0..<20 { await Task.yield() }
        #expect(!p.isPlaying && p.current?.libraryTrack?.id == "b" && spy.loads.last?.autoplay == false)
    }
}

@MainActor
private final class PauseSpy: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?
    var volume: Float = 1
    var onPause: () -> Void = {}
    var loads: [(url: URL, autoplay: Bool)] = []
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        loads.append((source.url, autoplay))
        if autoplay { onEvent?(.started) }
    }
    func preload(_ next: AudioSource?) {}
    func play() {}
    func pause() { onPause(); onEvent?(.paused) }
    func seek(to time: TimeInterval) {}
    func stop() {}
}
