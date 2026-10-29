import Foundation
import Observation
import PolyphonyKit
import SwiftUI

/// The app's state: who's signed in where, the clients for that server, and the player.
@MainActor
@Observable
final class AppModel {
    enum State { case loading, signedOut, signedIn }

    private(set) var state: State = .loading
    private(set) var server: SavedServer?
    private(set) var subsonic: SubsonicClient?
    private(set) var continuo: ContinuoService?
    /// Library albums holding single songs (continuo): left out of the album lists unless asked for.
    private(set) var singleSongAlbumIDs: Set<String> = []
    /// What the app is saying right now ("Requested Parachutes"), shown briefly.
    var notice: String?
    /// Where the window is: the sidebar's choice and the pages opened from it. Here rather than in the
    /// view so menu commands (⌘F, ⌘L) can move it.
    var sidebar: SidebarItem? = .home
    var path = NavigationPath()
    /// The full-window Now Playing view (big artwork and lyrics).
    var nowPlayingExpanded = false

    func go(to item: SidebarItem) {
        sidebar = item
        path = NavigationPath()
    }

    /// ⌘L: the album that's playing.
    func showCurrentAlbum() {
        guard let albumID = player.current?.libraryTrack?.albumID else { return }
        path.append(Route.album(albumID))
    }


    /// A Play now song being fetched before it can play.
    private(set) var pendingPlayNow: PlayNowStream?

    /// Lists already fetched this session, shown at once when a page opens again (then refreshed).
    @ObservationIgnored var albumLists: [SubsonicClient.AlbumListType: [Album]] = [:]
    @ObservationIgnored var artistList: [Artist]?
    /// Artists' names in their own script (continuo), by ContinuoService.nameKey of the library's name.
    private(set) var originalNames: [String: String] = [:]

    func originalName(of artist: String?) -> String? {
        guard let artist, let original = originalNames[ContinuoService.nameKey(artist)],
              original.caseInsensitiveCompare(artist) != .orderedSame else { return nil }
        return original
    }

    /// Unread notifications in continuo's inbox, for the sidebar's badge.
    private(set) var unreadCount = 0
    @ObservationIgnored private var inboxTask: Task<Void, Never>?

    func refreshUnread() async {
        guard let continuo else { return }
        if let page = try? await continuo.notifications(limit: 1, unreadOnly: true) { unreadCount = page.unreadCount }
    }

    func markRead(_ ids: [Int]?) async {
        guard let continuo else { return }
        if let count = try? await continuo.markRead(ids) { unreadCount = count }
    }

    /// Recently played albums and playlists with where they were left, for Continue Listening.
    private(set) var history = ListeningHistory()

    private var historyKey: String { "history." + (server?.address.host ?? "") + "." + (server?.subsonicUsername ?? "") }

    /// Picks a collection up where it was left (the same song, a few seconds from where it stopped).
    func resume(_ entry: RecentContext) async {
        guard let subsonic else { return }
        do {
            let tracks: [Track]
            switch entry.context.kind {
            case .album: tracks = try await subsonic.album(id: entry.context.id).tracks
            case .playlist: tracks = try await subsonic.playlist(id: entry.context.id).tracks
            default: return
            }
            let point = ResumePoint.of(tracks, entry)
            player.play(tracks, startAt: point.index, context: entry.context, position: TimeInterval(point.positionMs) / 1000)
        } catch {
            handle(error)
        }
    }

    func forget(_ context: PlayContext) {
        history.remove(context)
        saveHistory()
    }

    private func saveHistory() {
        if let data = try? JSONEncoder().encode(history) { UserDefaults.standard.set(data, forKey: historyKey) }
    }

    /// The user's playlists, for "Add to Playlist" menus (which can't load anything as they open).
    private(set) var playlists: [Playlist] = []

    func refreshPlaylists() async {
        if let list = try? await subsonic?.playlists() { playlists = list }
    }

    let player: PlayerController
    let downloads = Downloads()
    let speakers = SpeakerDiscovery()
    /// Where the music plays: this Mac, or a DLNA speaker.
    private(set) var speaker: Renderer?
    @ObservationIgnored private let macEngine: AVPlayerEngine
    @ObservationIgnored private var speakerEngine: DLNAEngine?

    /// The address speakers are given instead of the server's, per server: they fetch the audio
    /// themselves and many can't do HTTPS (for continuo: http://192.168.0.101, its LAN door).
    private(set) var receiverAddress = ""

    private var receiverKey: String { "receiver." + (server?.address.host ?? "") }

    func setReceiverAddress(_ address: String) {
        receiverAddress = address.trimmingCharacters(in: .whitespaces)
        UserDefaults.standard.set(receiverAddress, forKey: receiverKey)
    }

    /// Plays on [renderer] (nil: this Mac), carrying on from where the song is.
    func play(on renderer: Renderer?) {
        speakerEngine?.detach()
        if let renderer {
            let engine = DLNAEngine(control: DLNAControl(renderer: renderer))
            speakerEngine = engine
            speaker = renderer
            player.switchOutput(to: engine)
        } else {
            speakerEngine = nil
            speaker = nil
            player.switchOutput(to: macEngine)
        }
    }

    /// A URL a speaker can fetch: the receiver address in place of the server's own.
    private func forSpeaker(_ url: URL) -> URL {
        guard !receiverAddress.isEmpty, var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let base = URLComponents(string: receiverAddress.contains("://") ? receiverAddress : "http://" + receiverAddress)
        else { return url }
        parts.scheme = base.scheme
        parts.host = base.host
        parts.port = base.port
        parts.path = base.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).isEmpty
            ? parts.path : "/" + base.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + parts.path
        return parts.url ?? url
    }

    private static func mime(for container: String?) -> String {
        switch container?.lowercased() {
        case "mp3": "audio/mpeg"
        case "m4a", "aac", "alac": "audio/mp4"
        case "ogg", "oga": "audio/ogg"
        case "opus": "audio/opus"
        case "wav": "audio/wav"
        default: "audio/flac"
        }
    }
    @ObservationIgnored private let nowPlaying: NowPlayingCenter
    @ObservationIgnored private let store = KeychainServerStore()
    @ObservationIgnored private let arrivals = ArrivalNotifier()

    init() {
        // Every stored property is set before `self` is used.
        let center = NowPlayingCenter()
        nowPlaying = center
        let engine = AVPlayerEngine()
        macEngine = engine
        player = PlayerController(engine: engine, nowPlaying: center)
        center.attach(player)
        player.resolve = { [weak self] entry in await self?.source(for: entry) }
        player.artwork = { [weak self] entry in self?.artworkURL(for: entry, size: 600) }
        player.scrobble = { [weak self] track, submission in
            guard let subsonic = self?.subsonic else { return }
            Task { try? await subsonic.scrobble(trackID: track.id, submission: submission) }
        }
        player.history = { [weak self] context, trackID, index, ms in
            guard let self else { return }
            self.history.record(context, trackID: trackID, index: index, positionMs: ms)
            self.saveHistory()
        }
        player.persist = { snapshot in
            let defaults = UserDefaults.standard
            if let snapshot, let data = try? JSONEncoder().encode(snapshot) {
                defaults.set(data, forKey: Self.queueKey)
            } else {
                defaults.removeObject(forKey: Self.queueKey)
            }
        }
    }

    private static let queueKey = "queue.snapshot"

    /// Rate songs with stars (1–5) instead of the heart, as the Android app's Ratings mode.
    private(set) var usesRatings = UserDefaults.standard.bool(forKey: "feedback.ratings")

    func setUsesRatings(_ on: Bool) {
        usesRatings = on
        UserDefaults.standard.set(on, forKey: "feedback.ratings")
    }

    /// Streaming quality: 0 for the file as it is, else transcoded to MP3 at this bitrate (kbps).
    private(set) var streamingBitrate = UserDefaults.standard.integer(forKey: "streaming.bitrate")

    func setStreamingBitrate(_ kbps: Int) {
        streamingBitrate = kbps
        UserDefaults.standard.set(kbps, forKey: "streaming.bitrate")
    }

    var isContinuo: Bool { continuo != nil }

    // MARK: Signing in and out

    func start() async {
        if let saved = await store.load() {
            await attach(saved)
        } else {
            state = .signedOut
        }
    }

    func signIn(address: String, username: String, password: String) async throws {
        let device = Host.current().localizedName ?? "Mac"
        let saved = try await Connector.connect(address: address, username: username, password: password,
                                                deviceName: "Polyphony on \(device)")
        await store.save(saved)
        await attach(saved)
    }

    func signOut() async {
        if speaker != nil { play(on: nil) }
        player.stop()
        arrivals.stop()
        inboxTask?.cancel()
        unreadCount = 0
        if let account = continuo?.account { await account.signOut() }
        await store.save(nil)
        UserDefaults.standard.removeObject(forKey: Self.queueKey)
        downloads.detach()
        server = nil
        subsonic = nil
        continuo = nil
        singleSongAlbumIDs = []
        albumLists = [:]
        artistList = nil
        playlists = []
        originalNames = [:]
        state = .signedOut
    }

    private func attach(_ saved: SavedServer) async {
        server = saved
        let client = SubsonicClient(credentials: saved.subsonic)
        subsonic = client
        downloads.attach(server: saved, library: client) { track in client.downloadURL(trackID: track.id) }
        if saved.kind == .continuo {
            let account = ContinuoAccount(client: ContinuoClient(baseURL: saved.address),
                                          store: ServerSessionStore(store))
            _ = await account.restore()
            let service = ContinuoService(account: account)
            continuo = service
            arrivals.start(service)
            inboxTask?.cancel()
            inboxTask = Task { [weak self] in
                while !Task.isCancelled {
                    await self?.refreshUnread()
                    try? await Task.sleep(for: .seconds(60))
                }
            }
        } else {
            continuo = nil
        }
        // The user's own receiver address, else the one continuo hands out (continuo#83).
        receiverAddress = UserDefaults.standard.string(forKey: receiverKey) ?? saved.session?.library.receiverBaseUrl ?? ""
        if let data = UserDefaults.standard.data(forKey: historyKey),
           let saved = try? JSONDecoder().decode(ListeningHistory.self, from: data) {
            history = saved
        } else {
            history = ListeningHistory()
        }
        state = .signedIn
        // Where last session left off, paused: Play carries on.
        if player.current == nil, let data = UserDefaults.standard.data(forKey: Self.queueKey),
           let snapshot = try? JSONDecoder().decode(PlayQueue.Snapshot.self, from: data) {
            player.restore(snapshot)
        }
        await refreshSingles()
        await refreshPlaylists()
        if let continuo { originalNames = (try? await continuo.originalArtistNames()) ?? [:] }
    }

    func refreshSingles() async {
        guard let continuo else { return }
        if let singles = try? await continuo.singleSongAlbums() {
            singleSongAlbumIDs = Set(singles.keys)
        }
    }

    /// A call that failed because the session ended sends the app back to sign-in.
    func handle(_ error: Error) {
        if let e = error as? ContinuoError, e.needsSignIn {
            Task { await signOut() }
            return
        }
        notice = (error as? CustomStringConvertible)?.description ?? error.localizedDescription
    }

    // MARK: Artwork and audio

    func coverURL(_ coverArtID: String?, size: Int = 300) -> URL? {
        guard let coverArtID, let subsonic else { return nil }
        return subsonic.coverArtURL(id: coverArtID, size: size)
    }

    func artworkURL(for entry: QueueEntry, size: Int) -> URL? {
        switch entry {
        case let .track(t): coverURL(t.coverArtID ?? t.albumID, size: size)
        case let .playNow(_, c): c.artworkURL
        }
    }

    private func source(for entry: QueueEntry) async -> AudioSource? {
        switch entry {
        case let .track(t):
            let bitrate = streamingBitrate
            if speaker == nil {
                // A downloaded copy plays from disk: no network, original quality.
                if let local = downloads.localURL(t.id) {
                    downloads.played(t.id)
                    return AudioSource(url: local)
                }
                return subsonic.map {
                    AudioSource(url: bitrate > 0 ? $0.streamURL(trackID: t.id, maxBitRateKbps: bitrate, format: "mp3")
                                                 : $0.streamURL(trackID: t.id))
                }
            }
            // A speaker fetches the audio itself, from an address it can reach, and shows the song.
            guard let subsonic else { return nil }
            let url = bitrate > 0 ? subsonic.streamURL(trackID: t.id, maxBitRateKbps: bitrate, format: "mp3") : subsonic.streamURL(trackID: t.id)
            let art = coverURL(t.coverArtID ?? t.albumID, size: 600).map(forSpeaker)
            return AudioSource(url: forSpeaker(url), info: .init(title: t.title, artist: t.artistName, album: t.albumName, artworkURL: art,
                                                                mimeType: bitrate > 0 ? "audio/mpeg" : Self.mime(for: t.container),
                                                                durationMs: Int(t.duration * 1000)))
        case let .playNow(stream, _):
            guard let continuo, let token = try? await continuo.account.accessToken() else { return nil }
            return AudioSource(url: continuo.playNowAudioURL(id: stream.id), headers: ["Authorization": "Bearer \(token)"])
        }
    }

    // MARK: Catalogue actions

    /// Plays a song from the catalogue: from the library when it's there, else through Play now
    /// (continuo fetches it from Soulseek and streams it while it downloads).
    func play(_ track: CatalogTrack) async {
        if case let .inLibrary(albumID?) = track.availability, let subsonic,
           let album = try? await subsonic.album(id: albumID),
           let index = album.tracks.firstIndex(where: { $0.title.caseInsensitiveCompare(track.title) == .orderedSame }) {
            player.play(album.tracks, startAt: index, title: album.name)
            return
        }
        await playNow(track)
    }

    func playNow(_ track: CatalogTrack) async {
        guard let continuo else { return }
        do {
            var stream = try await continuo.startPlayNow(track)
            pendingPlayNow = stream
            // Poll until there's enough to play: usually seconds, up to a couple of minutes.
            for _ in 0..<180 where !stream.playable && !stream.failed {
                try await Task.sleep(for: .seconds(1))
                stream = try await continuo.playNow(id: stream.id)
                pendingPlayNow = stream
            }
            pendingPlayNow = nil
            if stream.failed || !stream.playable {
                notice = stream.error ?? "Couldn't get \(track.title) right now."
                return
            }
            player.play(entries: [.playNow(stream, track)], title: track.title)
        } catch {
            pendingPlayNow = nil
            handle(error)
        }
    }

    func request(_ album: CatalogAlbum) async {
        guard let continuo, let rg = album.releaseGroupID else { return }
        do {
            switch try await continuo.requestAlbum(releaseGroupID: rg) {
            case .requested: notice = "Requested \(album.title). It'll appear in Requests."
            case .alreadyRequested: notice = "\(album.title) is already on its way."
            case .inLibrary: notice = "\(album.title) is already in your library."
            case .addedBack: notice = "\(album.title) is back in your library."
            }
        } catch {
            handle(error)
        }
    }

    func request(_ track: CatalogTrack) async {
        guard let continuo, let recording = track.recordingID, let rg = track.albumReleaseGroupID else { return }
        do {
            _ = try await continuo.requestTrack(recordingID: recording, releaseGroupID: rg)
            notice = "Requested \(track.title)."
        } catch {
            handle(error)
        }
    }
}
