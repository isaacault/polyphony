import Foundation
import Observation

/// Something audio can be played from: a URL, and the headers it needs (a Play now stream needs
/// continuo's bearer token).
public struct AudioSource: Sendable, Hashable {
    public var url: URL
    public var headers: [String: String]
    /// What a speaker shows while it plays (DLNA renderers display it); the Mac's own engine ignores it.
    public var info: Info?

    public struct Info: Sendable, Hashable {
        public var title: String
        public var artist: String?
        public var album: String?
        public var artworkURL: URL?
        public var mimeType: String
        public var durationMs: Int?

        public init(title: String, artist: String? = nil, album: String? = nil, artworkURL: URL? = nil,
                    mimeType: String = "audio/flac", durationMs: Int? = nil) {
            self.title = title
            self.artist = artist
            self.album = album
            self.artworkURL = artworkURL
            self.mimeType = mimeType
            self.durationMs = durationMs
        }
    }

    public init(url: URL, headers: [String: String] = [:], info: Info? = nil) {
        self.url = url
        self.headers = headers
        self.info = info
    }
}

public enum AudioEvent: Sendable, Equatable {
    case started
    case paused
    case buffering(Bool)
    case position(TimeInterval, duration: TimeInterval?)
    /// The current source played to its end.
    case ended
    case failed(String)
}

/// The platform's player: AVFoundation on Apple, a fake in tests. One source at a time, plus a hint
/// of the next so the engine can start loading it (gapless where it can).
@MainActor
public protocol AudioEngine: AnyObject {
    var onEvent: ((AudioEvent) -> Void)? { get set }
    var volume: Float { get set }
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool)
    func preload(_ next: AudioSource?)
    func play()
    func pause()
    func seek(to time: TimeInterval)
    func stop()
    /// ReplayGain's level for the song, on top of the user's volume (engines without one ignore it).
    func setGain(_ gain: Float)
    /// Playback speed (1 = as recorded); engines that can't change it ignore it.
    func setRate(_ rate: Float)
}

extension AudioEngine {
    public func setGain(_ gain: Float) {}
    public func setRate(_ rate: Float) {}
}

/// What the system shows about the song (Now Playing, Control Centre, media keys' owner). The app
/// supplies MPNowPlayingInfoCenter; tests a recorder.
@MainActor
public protocol NowPlayingPublisher: AnyObject {
    func publish(_ info: NowPlayingInfo?)
}

public struct NowPlayingInfo: Sendable, Equatable {
    public var title: String
    public var artist: String?
    public var album: String?
    public var duration: TimeInterval
    public var position: TimeInterval
    public var isPlaying: Bool
    public var artworkURL: URL?
}

/// The player as the UI sees it: the queue, what's playing, where, and the controls. Platform
/// specifics come in through [AudioEngine], [NowPlayingPublisher] and the resolve/scrobble closures.
@MainActor
@Observable
public final class PlayerController {
    public private(set) var queue = PlayQueue()
    public private(set) var isPlaying = false
    public private(set) var isBuffering = false
    public private(set) var position: TimeInterval = 0
    public private(set) var duration: TimeInterval = 0
    public private(set) var error: String?
    /// The sleep timer: pause at this time, or (with [sleepAtEndOfSong]) when the song ends.
    public private(set) var sleepAt: Date?
    public private(set) var sleepAtEndOfSong = false

    public var volume: Float {
        get { engine.volume }
        set { engine.volume = newValue }
    }

    @ObservationIgnored private var engine: AudioEngine
    @ObservationIgnored private weak var nowPlaying: NowPlayingPublisher?
    /// The source for an entry: a Subsonic stream URL, or continuo's Play now audio with its token.
    @ObservationIgnored public var resolve: @MainActor (QueueEntry) async -> AudioSource? = { _ in nil }
    @ObservationIgnored public var artwork: @MainActor (QueueEntry) -> URL? = { _ in nil }
    /// Library tracks only: "now playing" when a track starts, a counted play once [ScrobbleRule] says so.
    @ObservationIgnored public var scrobble: @MainActor (Track, _ submission: Bool) -> Void = { _, _ in }

    /// Called when there's something worth keeping for next launch (a new song, a pause, every half
    /// minute of play): the app writes it to disk.
    @ObservationIgnored public var persist: @MainActor (PlayQueue.Snapshot?) -> Void = { _ in }
    /// Where in its collection the queue is (a nil track: played to the end), for Continue listening.
    @ObservationIgnored public var history: @MainActor (PlayContext, _ trackID: String?, _ index: Int, _ positionMs: Int) -> Void = { _, _, _, _ in }
    @ObservationIgnored private var lastPersisted: TimeInterval = 0

    @ObservationIgnored public var now: @MainActor () -> Date = Date.init
    @ObservationIgnored private var playedOfCurrent: TimeInterval = 0
    @ObservationIgnored private var lastPosition: TimeInterval = 0
    @ObservationIgnored private var scrobbled = false
    @ObservationIgnored private var loadGeneration = 0

    /// Previous restarts the song instead of going back once this far in.
    public static let restartThreshold: TimeInterval = 3

    public init(engine: AudioEngine, nowPlaying: NowPlayingPublisher? = nil) {
        self.engine = engine
        self.nowPlaying = nowPlaying
        engine.onEvent = { [weak self] event in self?.handle(event) }
    }

    public var current: QueueEntry? { queue.current?.entry }

    // MARK: Starting

    public func play(_ tracks: [Track], startAt index: Int = 0, shuffle: Bool = false, title: String? = nil,
                     context: PlayContext? = nil, position: TimeInterval = 0) {
        play(entries: tracks.map(QueueEntry.track), startAt: index, shuffle: shuffle, title: title ?? context?.title,
             context: context, position: position)
    }

    public func play(entries: [QueueEntry], startAt index: Int = 0, shuffle: Bool = false, title: String? = nil,
                     context: PlayContext? = nil, position: TimeInterval = 0) {
        let repeatMode = queue.repeatMode
        queue = PlayQueue(entries, startAt: index, shuffle: shuffle, title: title)
        queue.repeatMode = repeatMode
        queue.context = context
        loadCurrent(autoplay: true, startAt: position)
    }

    public func playNext(_ entries: [QueueEntry]) {
        let wasEmpty = queue.isEmpty
        queue.playNext(entries)
        if wasEmpty { loadCurrent(autoplay: true) } else { preloadNext() }
    }

    public func addToQueue(_ entries: [QueueEntry]) {
        let wasEmpty = queue.isEmpty
        queue.append(entries)
        if wasEmpty { loadCurrent(autoplay: true) } else { preloadNext() }
    }

    /// Last launch's queue, loaded paused at its place: Play carries on.
    public func restore(_ snapshot: PlayQueue.Snapshot) {
        queue = PlayQueue(snapshot: snapshot)
        guard let item = queue.current else { return }
        loadGeneration += 1
        let generation = loadGeneration
        duration = item.entry.duration
        position = snapshot.position
        lastPosition = snapshot.position
        playedOfCurrent = 0
        scrobbled = false
        publishNowPlaying()
        Task { @MainActor in
            guard let source = await resolve(item.entry), generation == loadGeneration else { return }
            engine.load(source, startAt: snapshot.position, autoplay: false)
            preloadNext()
        }
    }

    private func save() {
        lastPersisted = position
        persist(queue.snapshot(position: position))
        if let context = queue.context, let item = queue.current, let track = item.entry.libraryTrack {
            // The index in the collection's own order (shuffle plays it in another).
            let index = queue.items.firstIndex { $0.id == item.id } ?? 0
            history(context, track.id, index, Int(position * 1000))
        }
    }

    // MARK: Outputs

    /// Moves playback to another engine (the Mac's own, or a speaker's): the old one goes quiet, the
    /// song carries on on the new one from where it was, playing if it was.
    public func switchOutput(to newEngine: AudioEngine) {
        guard newEngine !== engine else { return }
        let wasPlaying = isPlaying
        let at = position
        engine.onEvent = nil
        engine.stop()
        engine = newEngine
        newEngine.onEvent = { [weak self] event in self?.handle(event) }
        newEngine.setRate(speed)
        isPlaying = false
        guard queue.current != nil else { return }
        loadCurrent(autoplay: wasPlaying, startAt: at)
    }

    // MARK: Sleep timer

    public enum SleepTimer: Sendable, Equatable {
        case minutes(Int)
        case endOfSong
    }

    public func setSleepTimer(_ timer: SleepTimer?) {
        switch timer {
        case let .minutes(m)?:
            sleepAt = now().addingTimeInterval(TimeInterval(m * 60))
            sleepAtEndOfSong = false
        case .endOfSong?:
            sleepAt = nil
            sleepAtEndOfSong = true
        case nil:
            sleepAt = nil
            sleepAtEndOfSong = false
        }
    }

    private func sleepIfDue() {
        guard let sleepAt, now() >= sleepAt else { return }
        self.sleepAt = nil
        pause()
    }

    // MARK: Controls

    public func togglePlay() {
        isPlaying ? pause() : resume()
    }

    public func resume() {
        guard current != nil else { return }
        engine.play()
    }

    public func pause() {
        engine.pause()
    }

    public func next() {
        guard queue.next() else { return }
        loadCurrent(autoplay: true)
    }

    public func previous() {
        if position > Self.restartThreshold || !queue.previous() {
            seek(to: 0)
            return
        }
        loadCurrent(autoplay: true)
    }

    public func seek(to time: TimeInterval) {
        let t = max(0, duration > 0 ? min(time, duration) : time)
        engine.seek(to: t)
        position = t
        lastPosition = t
        publishNowPlaying()
    }

    public func jump(to id: UUID) {
        queue.jump(to: id)
        loadCurrent(autoplay: true)
    }

    public func setShuffle(_ on: Bool) {
        queue.setShuffle(on)
        preloadNext()
    }

    public func cycleRepeat() {
        let all = RepeatMode.allCases
        queue.repeatMode = all[(all.firstIndex(of: queue.repeatMode)! + 1) % all.count]
        preloadNext()
    }

    public func remove(_ id: UUID) {
        let wasCurrent = queue.current?.id == id
        queue.remove(id)
        if queue.isEmpty {
            stop()
        } else if wasCurrent {
            loadCurrent(autoplay: isPlaying)
        } else {
            preloadNext()
        }
    }

    public func moveUpNext(from: Int, to: Int) {
        queue.moveUpNext(from: from, to: to)
        preloadNext()
    }

    public func clearUpNext() {
        queue.clearUpNext()
        preloadNext()
    }

    public func stop() {
        engine.stop()
        queue = PlayQueue()
        persist(nil)
        isPlaying = false
        position = 0
        duration = 0
        nowPlaying?.publish(nil)
    }

    /// A Play now stream moved on (more downloaded, ready, failed): keep the queue's copy current.
    public func update(_ id: UUID, to entry: QueueEntry) {
        queue.update(id, to: entry)
    }

    // MARK: The engine

    /// Playback speed, kept from song to song (as the Android app's Speed).
    public var speed: Float = 1 {
        didSet { engine.setRate(speed) }
    }

    public static let speeds: [Float] = [0.75, 1, 1.25, 1.5, 2]

    /// Levelling loudness between songs (Settings > Playback).
    public var replayGain = ReplayGain.Settings() {
        didSet { applyGain() }
    }

    private func applyGain() {
        let track = queue.current?.entry.libraryTrack
        let neighbour = (queue.nextOnEnd ?? queue.history.last)?.entry.libraryTrack
        engine.setGain(ReplayGain.volume(for: track, replayGain, sameAlbumAsNeighbour: ReplayGain.sameAlbum(track, neighbour)))
    }

    private func loadCurrent(autoplay: Bool, startAt: TimeInterval = 0) {
        guard let item = queue.current else {
            stop()
            return
        }
        applyGain()
        loadGeneration += 1
        let generation = loadGeneration
        position = startAt
        lastPosition = startAt
        playedOfCurrent = 0
        scrobbled = false
        error = nil
        duration = item.entry.duration
        publishNowPlaying()
        save()
        Task { @MainActor in
            guard let source = await resolve(item.entry) else {
                if generation == loadGeneration { error = "Can't play \(item.entry.title)." }
                return
            }
            // A newer choice (next pressed again) wins over a slow resolve.
            guard generation == loadGeneration else { return }
            engine.load(source, startAt: startAt, autoplay: autoplay)
            if let track = item.entry.libraryTrack { scrobble(track, false) }
            preloadNext()
        }
    }

    private func preloadNext() {
        guard let next = queue.nextOnEnd, next.id != queue.current?.id else {
            engine.preload(nil)
            return
        }
        let generation = loadGeneration
        Task { @MainActor in
            let source = await resolve(next.entry)
            guard generation == loadGeneration else { return }
            engine.preload(source)
        }
    }

    private func handle(_ event: AudioEvent) {
        switch event {
        case .started:
            isPlaying = true
            isBuffering = false
        case .paused:
            isPlaying = false
            save()
        case let .buffering(on):
            isBuffering = on
        case let .position(time, total):
            // Count only real listening: a seek forward isn't played time.
            let delta = time - lastPosition
            if delta > 0 && delta < 5 { playedOfCurrent += delta }
            lastPosition = time
            position = time
            if abs(time - lastPersisted) >= 30 { save() }
            sleepIfDue()
            if let total, total > 0 { duration = total }
            if !scrobbled, let track = current?.libraryTrack, ScrobbleRule.counts(played: playedOfCurrent, duration: duration) {
                scrobbled = true
                scrobble(track, true)
            }
        case .ended:
            if sleepAtEndOfSong {
                // The timer stops at the end of this song: move on to the next, paused.
                sleepAtEndOfSong = false
                if queue.advanceOnEnd() { loadCurrent(autoplay: false) }
                isPlaying = false
            } else if queue.advanceOnEnd() {
                loadCurrent(autoplay: true)
            } else {
                isPlaying = false
                position = duration
                // The collection was played to its end: Continue listening starts it over.
                if let context = queue.context { history(context, nil, 0, 0) }
            }
        case let .failed(message):
            DiagnosticLog.shared.log("playback", "\(current?.title ?? "?"): \(message)")
            error = message
            isPlaying = false
        }
        publishNowPlaying()
    }

    private func publishNowPlaying() {
        guard let entry = current else {
            nowPlaying?.publish(nil)
            return
        }
        nowPlaying?.publish(NowPlayingInfo(title: entry.title, artist: entry.artist, album: entry.album,
                                           duration: duration, position: position, isPlaying: isPlaying,
                                           artworkURL: artwork(entry)))
    }
}
