import AppKit
import Foundation
import MediaPlayer
import PolyphonyKit

/// Now Playing in Control Centre and the menu bar, and the media keys (F7–F9, headphone buttons),
/// which macOS sends to whichever app last published Now Playing.
@MainActor
final class NowPlayingCenter: NowPlayingPublisher {
    private weak var player: PlayerController?
    private var artworkURL: URL?
    private var artwork: MPMediaItemArtwork?

    func attach(_ player: PlayerController) {
        self.player = player
        let commands = MPRemoteCommandCenter.shared()
        // MediaPlayer calls these on the main thread.
        func on(_ command: MPRemoteCommand, _ action: @escaping @MainActor (PlayerController) -> Void) {
            command.addTarget { [weak self] _ in
                MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
                    guard let player = self?.player else { return .noActionableNowPlayingItem }
                    action(player)
                    return .success
                }
            }
        }
        on(commands.playCommand) { $0.resume() }
        on(commands.pauseCommand) { $0.pause() }
        on(commands.togglePlayPauseCommand) { $0.togglePlay() }
        on(commands.nextTrackCommand) { $0.next() }
        on(commands.previousTrackCommand) { $0.previous() }
        commands.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let time = event.positionTime
            return MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
                self?.player?.seek(to: time)
                return .success
            }
        }
    }

    func publish(_ info: NowPlayingInfo?) {
        let center = MPNowPlayingInfoCenter.default()
        guard let info else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            return
        }
        var dict: [String: Any] = [
            MPMediaItemPropertyTitle: info.title,
            MPMediaItemPropertyPlaybackDuration: info.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: info.position,
            MPNowPlayingInfoPropertyPlaybackRate: info.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artist = info.artist { dict[MPMediaItemPropertyArtist] = artist }
        if let album = info.album { dict[MPMediaItemPropertyAlbumTitle] = album }
        if info.artworkURL == artworkURL, let artwork {
            dict[MPMediaItemPropertyArtwork] = artwork
        } else {
            loadArtwork(info.artworkURL)
        }
        center.nowPlayingInfo = dict
        center.playbackState = info.isPlaying ? .playing : .paused
    }

    private func loadArtwork(_ url: URL?) {
        artworkURL = url
        artwork = nil
        guard let url else { return }
        Task {
            guard let (data, _) = try? await URLSession.shared.data(from: url), let image = NSImage(data: data) else { return }
            guard url == artworkURL else { return }
            let art = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            artwork = art
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = art
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}
