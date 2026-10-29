import AVFoundation
import Foundation
import PolyphonyKit

/// PolyphonyKit's AudioEngine on AVFoundation. An AVQueuePlayer holds the current item and the
/// preloaded next one, so one song runs into the next without a gap; when the controller then asks
/// for the song the queue player has already moved on to, it's left playing rather than reloaded.
@MainActor
final class AVPlayerEngine: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?

    var volume: Float {
        get { player.volume }
        set { player.volume = newValue }
    }

    private let player = AVQueuePlayer()
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var endObserver: NSObjectProtocol?
    private var preloaded: (source: AudioSource, item: AVPlayerItem)?
    private var currentSource: AudioSource?
    /// The item whose end means "the song ended" (the queue player may have moved on by itself).
    private var playingItem: AVPlayerItem?

    init() {
        player.automaticallyWaitsToMinimizeStalling = true
        player.actionAtItemEnd = .advance
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
                                                      queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.reportPosition(time) }
        }
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in self?.report(status) }
        })
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification,
                                                             object: nil, queue: .main) { [weak self] note in
            let item = note.object as? AVPlayerItem
            MainActor.assumeIsolated { self?.itemEnded(item) }
        }
    }

    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {
        // The queue player already moved on to the preloaded song: keep it playing.
        if source == currentSource, player.currentItem != nil {
            if autoplay { player.play() }
            return
        }
        let item: AVPlayerItem
        if let preloaded, preloaded.source == source {
            item = preloaded.item
        } else {
            item = Self.item(for: source)
        }
        preloaded = nil
        player.removeAllItems()
        player.insert(item, after: nil)
        currentSource = source
        playingItem = item
        watchFailures(of: item)
        if startAt > 0 { player.seek(to: CMTime(seconds: startAt, preferredTimescale: 600)) }
        if autoplay { player.play() } else { player.pause() }
    }

    func preload(_ next: AudioSource?) {
        // Drop any earlier preload that's still queued behind the current item.
        for item in player.items().dropFirst() { player.remove(item) }
        guard let next else {
            preloaded = nil
            return
        }
        let item = Self.item(for: next)
        preloaded = (next, item)
        watchFailures(of: item)
        if player.canInsert(item, after: player.currentItem) {
            player.insert(item, after: player.currentItem)
        }
    }

    func play() { player.play() }

    func pause() { player.pause() }

    func seek(to time: TimeInterval) {
        player.seek(to: CMTime(seconds: time, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func stop() {
        player.pause()
        player.removeAllItems()
        currentSource = nil
        playingItem = nil
        preloaded = nil
    }

    // MARK: Events

    private static func item(for source: AudioSource) -> AVPlayerItem {
        // Play now audio needs continuo's bearer token on every request (seeks are range requests).
        let options: [String: Any] = source.headers.isEmpty ? [:] : ["AVURLAssetHTTPHeaderFieldsKey": source.headers]
        let item = AVPlayerItem(asset: AVURLAsset(url: source.url, options: options))
        item.preferredForwardBufferDuration = 30
        return item
    }

    private func itemEnded(_ item: AVPlayerItem?) {
        guard let item, item === playingItem else { return }
        // With a preloaded next item the queue player moves on by itself; the controller is told
        // the song ended and asks for the next one, which `load` then sees is already playing.
        if let preloaded, player.currentItem === preloaded.item {
            currentSource = preloaded.source
            playingItem = preloaded.item
            self.preloaded = nil
        } else {
            currentSource = nil
            playingItem = nil
        }
        onEvent?(.ended)
    }

    private func watchFailures(of item: AVPlayerItem) {
        observations.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            let message = item.error?.localizedDescription ?? "Couldn't play this song."
            Task { @MainActor in
                // A preloaded song failing isn't the current song failing.
                guard let self, item === self.playingItem else { return }
                self.onEvent?(.failed(message))
            }
        })
    }

    private func report(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .playing:
            onEvent?(.buffering(false))
            onEvent?(.started)
        case .paused:
            onEvent?(.paused)
        case .waitingToPlayAtSpecifiedRate:
            onEvent?(.buffering(true))
        @unknown default:
            break
        }
    }

    private func reportPosition(_ time: CMTime) {
        guard let item = player.currentItem else { return }
        let duration = item.duration.isNumeric ? item.duration.seconds : nil
        onEvent?(.position(time.seconds, duration: duration))
    }
}
