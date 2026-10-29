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
    /// A song's Song Info sheet, opened from its menu.
    var songInfo: Track?

    func go(to item: SidebarItem) {
        sidebar = item
        path = NavigationPath()
    }

    /// Opens a page over whatever is showing (a notification's album, a menu's Go to).
    func open(_ route: Route) {
        nowPlayingExpanded = false
        path.append(route)
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
        let to = renderer.map { "to \($0.name) (\($0.model ?? "?"), \($0.avTransport.host ?? "?"))" } ?? "to this Mac"
        DiagnosticLog.shared.log("output", to + (receiverAddress.isEmpty ? ", no receiver address" : ", receiver \(receiverAddress)"))
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

    /// A URL a speaker can fetch: the receiver address in place of the library's base URL (the
    /// Android app's rule, shared through contract/cases/receiver.json).
    private func forSpeaker(_ url: URL) -> URL {
        guard let base = server?.subsonic.baseURL.absoluteString else { return url }
        return URL(string: ReceiverURL.rewrite(url.absoluteString, baseURL: base, receiver: receiverAddress)) ?? url
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
    @ObservationIgnored private let outputs = OutputWatcher()

    /// Settings > Playback: pause when headphones go and the speakers would take over (on by default).
    private(set) var pauseOnDisconnect = UserDefaults.standard.object(forKey: "playback.pauseOnDisconnect") as? Bool ?? true

    func setPauseOnDisconnect(_ on: Bool) {
        pauseOnDisconnect = on
        UserDefaults.standard.set(on, forKey: "playback.pauseOnDisconnect")
    }

    init() {
        // Every stored property is set before `self` is used.
        let center = NowPlayingCenter()
        nowPlaying = center
        let engine = AVPlayerEngine()
        macEngine = engine
        player = PlayerController(engine: engine, nowPlaying: center)
        if let data = UserDefaults.standard.data(forKey: "playback.replayGain"),
           let saved = try? JSONDecoder().decode(ReplayGain.Settings.self, from: data) {
            player.replayGain = saved
        }
        center.attach(player)
        outputs.onHeadphonesGone = { [weak self] in
            guard let self, self.pauseOnDisconnect, self.speaker == nil, self.player.isPlaying else { return }
            DiagnosticLog.shared.log("output", "headphones gone: paused")
            self.player.pause()
        }
        outputs.start()
        player.resolve = { [weak self] entry in await self?.source(for: entry) }
        player.artwork = { [weak self] entry in self?.artworkURL(for: entry, size: 600) }
        player.scrobble = { [weak self] track, submission in
            guard let subsonic = self?.subsonic else { return }
            Task { try? await subsonic.scrobble(trackID: track.id, submission: submission) }
        }
        player.playNowScrobble = { [weak self] streamID, listenedAt in
            guard let continuo = self?.continuo else { return }
            Task { try? await continuo.scrobblePlayNow(id: streamID, listenedAt: listenedAt) }
        }
        player.history = { [weak self] context, trackID, index, ms in
            guard let self else { return }
            self.history.record(context, trackID: trackID, index: index, positionMs: ms)
            self.saveHistory()
        }
        player.persist = { [weak self] snapshot in
            guard let self, self.server != nil else { return }
            let defaults = UserDefaults.standard
            if let snapshot, let data = try? JSONEncoder().encode(snapshot) {
                defaults.set(data, forKey: self.queueKey)
            } else if !self.detaching {
                defaults.removeObject(forKey: self.queueKey)
            }
        }
    }

    /// Set while letting go of a server: stopping its player mustn't forget its queue.
    @ObservationIgnored private var detaching = false

    /// Each server's queue on its own: switching servers and back carries on where it was.
    private var queueKey: String { "queue.snapshot." + (server?.key ?? "") }

    /// Now Playing tinted with the cover's colours (the Android app's artwork colours in the player).
    private(set) var artworkColours = UserDefaults.standard.object(forKey: "appearance.artworkColours") as? Bool ?? true

    func setArtworkColours(_ on: Bool) {
        artworkColours = on
        UserDefaults.standard.set(on, forKey: "appearance.artworkColours")
    }

    /// Rate songs with stars (1–5) instead of the heart, as the Android app's Ratings mode.
    private(set) var usesRatings = UserDefaults.standard.bool(forKey: "feedback.ratings")

    func setUsesRatings(_ on: Bool) {
        usesRatings = on
        UserDefaults.standard.set(on, forKey: "feedback.ratings")
    }

    /// Streaming quality: 0 for the file as it is, else transcoded to MP3 at this bitrate (kbps).
    private(set) var streamingBitrate = UserDefaults.standard.integer(forKey: "streaming.bitrate")

    /// Levelling loudness between songs (ReplayGain), kept between launches.
    var replayGain: ReplayGain.Settings {
        get { player.replayGain }
        set {
            player.replayGain = newValue
            if let data = try? JSONEncoder().encode(newValue) { UserDefaults.standard.set(data, forKey: "playback.replayGain") }
        }
    }

    func setStreamingBitrate(_ kbps: Int) {
        streamingBitrate = kbps
        UserDefaults.standard.set(kbps, forKey: "streaming.bitrate")
    }

    var isContinuo: Bool { continuo != nil }

    // MARK: Signing in and out

    func start() async {
        let list = await store.load()
        servers = list.servers
        if let saved = list.active {
            await attach(saved)
        } else {
            state = .signedOut
        }
    }

    /// Every server signed in to (Settings > Servers switches between them).
    private(set) var servers: [SavedServer] = []
    /// Adding another server: the sign-in form, over the app.
    var addingServer = false

    func signIn(address: String, username: String, password: String) async throws {
        let device = Host.current().localizedName ?? "Mac"
        let saved: SavedServer
        do {
            saved = try await Connector.connect(address: address, username: username, password: password,
                                                deviceName: "Polyphony on \(device)")
        } catch {
            DiagnosticLog.shared.log("sign-in", "\(address) as \(username): \(error)")
            throw error
        }
        DiagnosticLog.shared.log("sign-in", "signed in to \(saved.address.host ?? "?") as \(saved.kind.rawValue)")
        var list = await store.load()
        list.add(saved)
        await store.save(list)
        servers = list.servers
        addingServer = false
        if server != nil { detach(to: .loading) }
        await attach(saved)
    }

    /// Shows another signed-in server's library: this one's queue is kept for when it comes back.
    func switchTo(_ key: String) async {
        guard key != server?.key else { return }
        var list = await store.load()
        guard list.activate(key), let next = list.active else { return }
        DiagnosticLog.shared.log("servers", "switched to \(next.address.host ?? "?")")
        await store.save(list)
        detach(to: .loading)
        await attach(next)
    }

    /// Signs out of the server showing; the next one signed in to takes over.
    func signOut() async {
        if let account = continuo?.account, !offline { await account.signOut() }
        var list = await store.load()
        if let key = server?.key { list.remove(key) }
        await store.save(list)
        servers = list.servers
        UserDefaults.standard.removeObject(forKey: queueKey)
        detach(to: list.active == nil ? .signedOut : .loading)
        if let next = list.active { await attach(next) }
    }

    /// Lets go of the server showing (its playback, downloads, caches), signed in or not.
    private func detach(to next: State = .signedOut) {
        if speaker != nil { play(on: nil) }
        detaching = true
        player.stop()
        detaching = false
        arrivals.stop()
        inboxTask?.cancel()
        reachabilityTask?.cancel()
        unreachable = false
        unreadCount = 0
        downloads.detach()
        server = nil
        subsonic = nil
        continuo = nil
        singleSongAlbumIDs = []
        albumLists = [:]
        artistList = nil
        playlists = []
        originalNames = [:]
        state = next
    }

    private func attach(_ saved: SavedServer) async {
        server = saved
        let client = SubsonicClient(credentials: saved.subsonic)
        subsonic = client
        downloads.attach(server: saved, library: client) { track in
            // The original file, unless this Mac can't play it: then MP3 at 320 kbps.
            AppleFormats.plays(track.container)
                ? (client.downloadURL(trackID: track.id), track.container)
                : (client.streamURL(trackID: track.id, maxBitRateKbps: AppleFormats.fallbackBitrateKbps, format: AppleFormats.fallbackFormat),
                   AppleFormats.fallbackFormat)
        }
        if saved.kind == .continuo {
            let account = ContinuoAccount(client: ContinuoClient(baseURL: saved.address),
                                          store: ServerSessionStore(store, key: saved.key))
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
        if player.current == nil, let data = UserDefaults.standard.data(forKey: queueKey),
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

    /// A call that failed because the session ended sends the app back to sign-in; one that couldn't
    /// reach the server at all puts the app offline (downloads still play) until it can again.
    func handle(_ error: Error) {
        // A page closed or a search moved on mid-request: nothing went wrong.
        if error is CancellationError { return }
        DiagnosticLog.shared.log("error", String(describing: error))
        if let e = error as? ContinuoError, e.needsSignIn {
            Task { await signOut() }
            return
        }
        if Self.isUnreachable(error) {
            goOffline()
            return
        }
        notice = (error as? CustomStringConvertible)?.description ?? error.localizedDescription
    }

    // MARK: Offline

    /// The server can't be reached (or Offline mode is on): Downloads still play; the rest waits.
    private(set) var unreachable = false
    /// Settings > Playback > Offline mode: only what's downloaded, no network.
    private(set) var offlineMode = UserDefaults.standard.bool(forKey: "offline.mode")
    var offline: Bool { offlineMode || unreachable }
    @ObservationIgnored private var reachabilityTask: Task<Void, Never>?

    func setOfflineMode(_ on: Bool) {
        offlineMode = on
        UserDefaults.standard.set(on, forKey: "offline.mode")
        DiagnosticLog.shared.log("offline", on ? "offline mode on" : "offline mode off")
    }

    nonisolated static func isUnreachable(_ error: Error) -> Bool {
        (error as? TransportError)?.unreachable ?? TransportError.isUnreachable(error)
    }

    /// Unreachable: try the server every 30 seconds until it answers.
    private func goOffline() {
        guard !unreachable else { return }
        unreachable = true
        DiagnosticLog.shared.log("offline", "can't reach \(server?.address.host ?? "the server")")
        reachabilityTask?.cancel()
        reachabilityTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard let self, let subsonic = self.subsonic else { return }
                if (try? await subsonic.ping()) != nil {
                    self.unreachable = false
                    self.albumLists = [:]
                    DiagnosticLog.shared.log("offline", "back online")
                    return
                }
            }
        }
    }

    // MARK: Diagnostics

    /// Help > Save Diagnostics: what's set up and what went on lately, with no passwords or tokens.
    func diagnostics() -> String {
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))"
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        let rows = servers.map { s in
            Diagnostics.Server(kind: s.kind.rawValue, host: s.address.host ?? s.address.absoluteString, user: s.subsonicUsername,
                               showing: s.key == server?.key,
                               receiverAddress: UserDefaults.standard.string(forKey: "receiver." + (s.address.host ?? ""))
                                   ?? s.session?.library.receiverBaseUrl,
                               libraryHost: s.subsonic.baseURL.host)
        }
        let settings: [(String, String)] = [
            ("Streaming quality", streamingBitrate == 0 ? "Original" : "MP3 \(streamingBitrate) kbps"),
            ("Volume levelling", "\(replayGain.mode.rawValue), pre-amp \(replayGain.preampDb) dB, prevent clipping \(replayGain.preventClipping)"),
            ("Mark songs with", usesRatings ? "stars" : "hearts"),
            ("Output", speaker.map { "\($0.name) (\($0.model ?? "?"))" } ?? "this Mac"),
            ("Speakers found", speakers.renderers.map { "\($0.name) (\($0.avTransport.host ?? "?"))" }.joined(separator: ", ")),
            ("Downloads", "\(downloads.index.entries.count) songs, \(ByteCountFormatter.string(fromByteCount: downloads.usedBytes, countStyle: .file)), limit \(downloads.limitGB == 0 ? "none" : "\(downloads.limitGB) GB"), \(downloads.active.count) queued, \(downloads.failed.count) failed"),
            ("Playing", player.current.map { "\($0.title), \(player.isPlaying ? "playing" : "paused") at \(Int(player.position)) s" } ?? "nothing"),
            ("Player error", player.error ?? "none"),
        ]
        let rules = downloads.rules.map { r in
            "\(r.target.label)\(r.enabled ? "" : " (paused)")\(r.maxTracks > 0 ? ", up to \(r.maxTracks)" : ""): \(downloads.index.entries.values.filter { $0.ruleID == r.id }.count) kept"
        }
        return Diagnostics.report(app: version, system: os, servers: rows, settings: settings,
                                  sections: [("Download rules", rules)], log: DiagnosticLog.shared.recent)
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

    /// The stream this Mac can play: as the file is, or MP3 at the chosen quality; a format
    /// AVFoundation can't play (Ogg, Opus, WMA…) comes as MP3 at 320 kbps at most.
    private func macStreamURL(_ t: Track, _ subsonic: SubsonicClient, bitrate: Int) -> URL {
        if bitrate > 0 { return subsonic.streamURL(trackID: t.id, maxBitRateKbps: bitrate, format: "mp3") }
        if !AppleFormats.plays(t.container) {
            return subsonic.streamURL(trackID: t.id, maxBitRateKbps: AppleFormats.fallbackBitrateKbps, format: AppleFormats.fallbackFormat)
        }
        return subsonic.streamURL(trackID: t.id)
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
                if offlineMode { return nil }
                return subsonic.map { AudioSource(url: macStreamURL(t, $0, bitrate: bitrate)) }
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
