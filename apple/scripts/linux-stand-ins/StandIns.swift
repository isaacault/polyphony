// Stand-ins for the Apple-only pieces the app's logic files use (SwiftUI, AVFoundation, MediaPlayer,
// Security, UserNotifications, Darwin sockets), with the real ones' signatures, so typecheck-app.sh
// can type-check those files on Linux. Keep them in step with Polyphony/Platform and Views.
import Foundation
import Observation
import PolyphonyKit
#if canImport(FoundationNetworking)
@_exported import FoundationNetworking
#endif

// SwiftUI
struct NavigationPath {
    init() {}
    mutating func append<V: Hashable>(_ value: V) {}
}

// Views/RootView.swift, Views/Components.swift
enum SidebarItem: String, Hashable, CaseIterable, Identifiable {
    case home, search, explore, artists, albums, songs, playlists, genres, favourites, downloads, inbox, requests, removed
    var id: String { rawValue }
}

enum Route: Hashable {
    case album(String)
    case artist(String)
    case playlist(String)
    case genre(String)
    case catalogAlbum(CatalogAlbum)
    case catalogArtist(CatalogArtist)
    case discoveryPlaylist(DiscoveryPlaylist)
}

// Platform/AVPlayerEngine.swift
@MainActor
final class AVPlayerEngine: AudioEngine {
    var onEvent: ((AudioEvent) -> Void)?
    var volume: Float = 1
    init() {}
    func setGain(_ gain: Float) {}
    func setRate(_ rate: Float) {}
    func load(_ source: AudioSource, startAt: TimeInterval, autoplay: Bool) {}
    func preload(_ next: AudioSource?) {}
    func play() {}
    func pause() {}
    func seek(to time: TimeInterval) {}
    func stop() {}
}

// Platform/NowPlayingCenter.swift
@MainActor
final class NowPlayingCenter: NowPlayingPublisher {
    init() {}
    func attach(_ player: PlayerController) {}
    func publish(_ info: NowPlayingInfo?) {}
}

// Platform/KeychainServerStore.swift
final class KeychainServerStore: ServerStore, @unchecked Sendable {
    func load() async -> ServerList { ServerList() }
    func save(_ list: ServerList) async {}
}

// Platform/ArrivalNotifier.swift
@MainActor
final class ArrivalNotifier {
    func start(_ service: ContinuoService) {}
    func stop() {}
}

// Platform/OutputWatcher.swift
@MainActor
final class OutputWatcher {
    var onHeadphonesGone: () -> Void = {}
    func start() {}
}

// Platform/SpeakerDiscovery.swift
@MainActor
@Observable
final class SpeakerDiscovery {
    private(set) var renderers: [Renderer] = []
    private(set) var searching = false
    func search() {}
}
