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
    private(set) var library: (any MusicLibrary)?
    private(set) var continuo: ContinuoService?
    /// Library albums holding single songs (continuo): left out of the album lists unless asked for.
    private(set) var singleSongAlbumIDs: Set<String> = []
    /// What the app is saying right now ("Requested Parachutes"), shown briefly.
    var notice: String?
    /// Where the window is: the sidebar's choice and the pages opened from it. Here rather than in the
    /// view so menu commands (⌘F, ⌘L) can move it.
    var sidebar: SidebarItem? = UserDefaults.standard.string(forKey: "sidebar.opensOn").flatMap(SidebarItem.init(rawValue:)) ?? .home
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
        guard let library else { return }
        do {
            let tracks: [Track]
            switch entry.context.kind {
            case .album: tracks = try await library.album(id: entry.context.id).tracks
            case .playlist: tracks = try await library.playlist(id: entry.context.id).tracks
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
        if let list = try? await library?.playlists() { playlists = list }
    }

    let player: PlayerController
    let downloads = Downloads()
    let speakers = SpeakerDiscovery()
    /// Where the music plays: this Mac, or a DLNA speaker.
    private(set) var speaker: Renderer?
    @ObservationIgnored private let macEngine: AVPlayerEngine
    @ObservationIgnored private var speakerEngine: DLNAEngine?
    /// What the speaker playing now says it can play.
    @ObservationIgnored private var speakerFormats = RendererFormats.unknownFormats
    /// Held while a speaker plays: a Mac asleep can't follow the songs.
    @ObservationIgnored private let keepAwake = SleepGuard()
    @ObservationIgnored private var playNowLinks = PlayNowLinkCache()

    /// The link a speaker plays a Play now song from; the same one each time it's asked for, so a
    /// queued song is recognised when it starts.
    private func playNowLink(_ id: String) async -> URL? {
        if let link = playNowLinks.fresh(id) { return link }
        guard let continuo, !playNowLinks.recentlyFailed(id) else { return nil }
        do {
            let link = try await continuo.playNowReceiverURL(id: id)
            playNowLinks.put(id, link)
            return link
        } catch {
            DiagnosticLog.shared.log("output", "no speaker link for Play now \(id): \(ErrorText.of(error))")
            playNowLinks.failed(id)
            return nil
        }
    }

    /// The address speakers are given instead of the server's, per server: they fetch the audio
    /// themselves and many can't do HTTPS (for continuo: http://192.168.0.101, its LAN door).
    private(set) var receiverAddress = ""

    private var receiverKey: String { "receiver." + (server?.address.host ?? "") }

    func setReceiverAddress(_ address: String) {
        receiverAddress = address.trimmingCharacters(in: .whitespaces)
        UserDefaults.standard.set(receiverAddress, forKey: receiverKey)
        checkHome()
    }

    /// Plays on [renderer] (nil: this Mac), carrying on from where the song is.
    func play(on renderer: Renderer?) {
        let to = renderer.map { "to \($0.name) (\($0.model ?? "?"), \($0.avTransport.host ?? "?"))" } ?? "to this Mac"
        DiagnosticLog.shared.log("output", to + (receiverAddress.isEmpty ? ", no receiver address" : ", receiver \(receiverAddress)"))
        speakerEngine?.detach()
        if let renderer {
            let engine = DLNAEngine(control: DLNAControl(renderer: renderer))
            engine.onPlayingChanged = { [weak self] in self?.keepAwake.hold($0) }
            speakerEngine = engine
            speakerFormats = .unknownFormats
            streaming.remember(.dlna(udn: renderer.udn, name: renderer.name))
            let control = DLNAControl(renderer: renderer)
            Task { [weak self] in
                let formats = await control.formats()
                guard self?.speaker?.udn == renderer.udn else { return }
                self?.speakerFormats = formats
            }
            speaker = renderer
            player.switchOutput(to: engine)
        } else {
            speakerEngine = nil
            speaker = nil
            keepAwake.hold(false)
            player.switchOutput(to: macEngine)
        }
    }

    /// A URL a speaker can fetch: the receiver address in place of the library's base URL (the
    /// Android app's rule, shared through contract/cases/receiver.json).
    private func forSpeaker(_ url: URL) -> URL {
        guard let base = server?.libraryBaseURL.absoluteString else { return url }
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

    /// Settings > Playback: carry on when headphones come back (off by default).
    private(set) var resumeOnConnect = UserDefaults.standard.bool(forKey: "playback.resumeOnConnect")

    func setResumeOnConnect(_ on: Bool) {
        resumeOnConnect = on
        UserDefaults.standard.set(on, forKey: "playback.resumeOnConnect")
    }

    /// Settings > Playback: the queue comes back at the next launch (on by default).
    private(set) var rememberQueue = UserDefaults.standard.object(forKey: "playback.rememberQueue") as? Bool ?? true

    func setRememberQueue(_ on: Bool) {
        rememberQueue = on
        UserDefaults.standard.set(on, forKey: "playback.rememberQueue")
        if !on { UserDefaults.standard.removeObject(forKey: queueKey) }
    }

    /// Settings > Playback > Scrobbling.
    func setScrobbles(_ on: Bool) {
        player.scrobbles = on
        UserDefaults.standard.set(on, forKey: "playback.scrobbles")
    }

    func setScrobbleThreshold(_ fraction: Double) {
        player.scrobbleThreshold = fraction
        UserDefaults.standard.set(fraction, forKey: "playback.scrobbleThreshold")
    }

    /// In ratings mode, what Top Rated means: this many stars and up.
    private(set) var topRatedMinimum = UserDefaults.standard.object(forKey: "library.topRatedMinimum") as? Int ?? 4

    func setTopRatedMinimum(_ stars: Int) {
        topRatedMinimum = min(max(stars, 1), 5)
        UserDefaults.standard.set(topRatedMinimum, forKey: "library.topRatedMinimum")
    }

    init() {
        // Every stored property is set before `self` is used.
        let center = NowPlayingCenter()
        nowPlaying = center
        let engine = AVPlayerEngine()
        macEngine = engine
        let defaults = UserDefaults.standard
        let rules: StreamingRules.Stored
        if let data = defaults.data(forKey: "streaming.rules"), let saved = try? JSONDecoder().decode(StreamingRules.Stored.self, from: data) {
            rules = saved
        } else {
            rules = StreamingRules.migrated(bitrateKbps: defaults.integer(forKey: "streaming.bitrate"))
        }
        streaming = StreamingRules(stored: rules)
        player = PlayerController(engine: engine, nowPlaying: center)
        if let data = UserDefaults.standard.data(forKey: "playback.replayGain"),
           let saved = try? JSONDecoder().decode(ReplayGain.Settings.self, from: data) {
            player.replayGain = saved
        }
        center.attach(player)
        player.preloadsNext = preloadNext
        player.scrobbles = defaults.object(forKey: "playback.scrobbles") as? Bool ?? true
        player.scrobbleThreshold = defaults.object(forKey: "playback.scrobbleThreshold") as? Double ?? 0.5
        outputs.onHeadphonesBack = { [weak self] in
            guard let self, self.resumeOnConnect, self.speaker == nil, !self.player.isPlaying, self.player.current != nil else { return }
            DiagnosticLog.shared.log("output", "headphones back: playing")
            self.player.resume()
        }
        streaming.persist = { stored in
            if let data = try? JSONEncoder().encode(stored) { UserDefaults.standard.set(data, forKey: "streaming.rules") }
        }
        streaming.onChange = { [weak self] in
            guard let self else { return }
            let d = self.streaming.decision()
            DiagnosticLog.shared.log("streaming", "now \(d.profile.name): \(d.explanation)")
            self.planFetchAhead()
            if self.streaming.policy.reloadCurrentTrackOnChange, self.speaker == nil, self.player.isPlaying,
               self.player.current?.libraryTrack.map({ !self.downloads.isDownloaded($0.id) }) == true {
                self.player.reloadCurrent()
            }
        }
        player.onStall = { [weak self] started in
            let now = Int(Date().timeIntervalSince1970 * 1000)
            self?.streaming.stall(started: started, nowMs: now)
        }
        player.onUpcomingChanged = { [weak self] in self?.planFetchAhead() }
        network.onChange = { [weak self] state in
            guard let self else { return }
            DiagnosticLog.shared.log("streaming", "network: \(state.kind.rawValue.lowercased())\(state.isMetered ? ", metered" : "")")
            self.streaming.setNetwork(state)
            self.checkHome()
            self.downloads.conditionsChanged()
        }
        network.start()
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(20))
                self?.streaming.tick(nowMs: Int(Date().timeIntervalSince1970 * 1000))
            }
        }
        outputs.onHeadphonesGone = { [weak self] in
            guard let self, self.pauseOnDisconnect, self.speaker == nil, self.player.isPlaying else { return }
            DiagnosticLog.shared.log("output", "headphones gone: paused")
            self.player.pause()
        }
        outputs.start()
        player.resolve = { [weak self] entry in await self?.source(for: entry) }
        player.artwork = { [weak self] entry in self?.artworkURL(for: entry, size: 600) }
        player.scrobble = { [weak self] track, submission in
            guard let library = self?.library else { return }
            Task { try? await library.scrobble(trackID: track.id, submission: submission) }
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
            guard let self, self.server != nil, self.rememberQueue else { return }
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
    private var wantsRatings = UserDefaults.standard.bool(forKey: "feedback.ratings")

    /// Jellyfin has likes, not star ratings: favourites there, as on Android.
    var usesRatings: Bool { wantsRatings && offersRatings }
    var offersRatings: Bool { server?.kind != .jellyfin }

    func setUsesRatings(_ on: Bool) {
        wantsRatings = on
        UserDefaults.standard.set(on, forKey: "feedback.ratings")
    }

    /// Which quality songs stream at: the rules in Settings > Streaming, and what the Mac knows now.
    let streaming: StreamingRules
    @ObservationIgnored let streamCache = StreamCache()
    @ObservationIgnored private let network = NetworkWatcher()
    @ObservationIgnored private var homeCheck: Task<Void, Never>?
    @ObservationIgnored private var fetchAhead: Task<Void, Never>?

    /// Settings > Playback > Streaming cache, kept between launches.
    private(set) var prefetchQueue = UserDefaults.standard.object(forKey: "playback.prefetchQueue") as? Bool ?? true
    private(set) var prefetchOnMetered = UserDefaults.standard.object(forKey: "playback.prefetchOnMetered") as? Bool ?? true
    private(set) var preloadNext = UserDefaults.standard.object(forKey: "playback.preloadNext") as? Bool ?? true

    func setPrefetchQueue(_ on: Bool) {
        prefetchQueue = on
        UserDefaults.standard.set(on, forKey: "playback.prefetchQueue")
        planFetchAhead()
    }

    func setPrefetchOnMetered(_ on: Bool) {
        prefetchOnMetered = on
        UserDefaults.standard.set(on, forKey: "playback.prefetchOnMetered")
        planFetchAhead()
    }

    func setPreloadNext(_ on: Bool) {
        preloadNext = on
        player.preloadsNext = on
        UserDefaults.standard.set(on, forKey: "playback.preloadNext")
    }

    /// Is the server there at its LAN address? Asked on sign-in and on each network change.
    private func checkHome() {
        homeCheck?.cancel()
        guard let server else {
            streaming.setAtHome(nil)
            return
        }
        let kind = streaming.network.kind
        let lan = HomeNetwork.lanBase(receiver: receiverAddress, baseURL: server.libraryBaseURL)
        let speaksSubsonic = server.kind != .jellyfin
        homeCheck = Task { [weak self] in
            let home = await HomeNetwork.check(kind: kind, lan: lan, subsonic: speaksSubsonic, transport: ShortTimeoutTransport())
            guard !Task.isCancelled, let self else { return }
            if home != self.streaming.atHome {
                DiagnosticLog.shared.log("streaming", "home network: \(home.map(String.init) ?? "unknown")")
            }
            self.streaming.setAtHome(home)
        }
    }

    /// The local stream of a song: how it should come, and its name in the stream cache.
    private func macStream(_ t: Track) -> (choice: StreamChoice, cacheName: String, container: String?) {
        let decision = streaming.localDecision(container: t.container, lossless: t.isLossless)
        let choice = StreamChoice.forMac(decision.profile, container: t.container)
        let container = choice.container(source: t.container)
        return (choice, "\(t.id)-\(choice.format ?? "raw")-\(choice.maxBitRateKbps ?? 0)", container)
    }

    private func streamURL(_ t: Track, _ choice: StreamChoice, _ library: any MusicLibrary) -> URL {
        library.streamURL(trackID: t.id, choice: choice)
    }

    /// Has the coming songs fetched whole, so a dropped connection doesn't stop the music at the next
    /// one. On this Mac's own player only: a speaker fetches for itself.
    private func planFetchAhead() {
        fetchAhead?.cancel()
        let budget = PrefetchPlan.budget(streaming.network, enabled: prefetchQueue && !offline && speaker == nil,
                                         onMetered: prefetchOnMetered)
        let upcoming = player.queue.upNext.map { item -> PrefetchPlan.Upcoming in
            let track = item.entry.libraryTrack
            return .init(trackID: track?.id ?? item.entry.title, durationMs: Int(item.entry.duration * 1000),
                         onDevice: track.map { downloads.isDownloaded($0.id) } ?? false, fetchable: track != nil)
        }
        let wanted = PrefetchPlan.pick(upcoming, budget)
        guard !wanted.isEmpty, let library else { return }
        let songs = player.queue.upNext.compactMap(\.entry.libraryTrack)
        fetchAhead = Task { [weak self] in
            var fetched: Int64 = 0
            for id in wanted {
                guard let self, !Task.isCancelled, let track = songs.first(where: { $0.id == id }),
                      fetched < self.streamCache.fetchBudgetBytes else { return }
                let stream = self.macStream(track)
                guard let bytes = await self.streamCache.fetch(self.streamURL(track, stream.choice, library), name: stream.cacheName,
                                                               container: stream.container) else { return }
                fetched += bytes
            }
        }
    }

    /// Levelling loudness between songs (ReplayGain), kept between launches.
    var replayGain: ReplayGain.Settings {
        get { player.replayGain }
        set {
            player.replayGain = newValue
            if let data = try? JSONEncoder().encode(newValue) { UserDefaults.standard.set(data, forKey: "playback.replayGain") }
        }
    }

    var isContinuo: Bool { continuo != nil }

    // MARK: Signing in and out

    /// Once per launch: the window's task calls it, and the window can close and open again while
    /// the music plays on.
    func start() async {
        guard state == .loading, server == nil else { return }
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
        library = nil
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
        let client = saved.library()
        library = client
        downloads.attach(server: saved, library: client) { [weak self] track in
            // The quality chosen for downloads: the original file by default, unless this Mac can't
            // play it (then MP3 at 320 kbps).
            let profile = self?.streaming.profile(self?.downloads.qualityProfileID) ?? .original
            let choice = StreamChoice.forMac(profile, container: track.container)
            if choice.isOriginal { return (client.downloadURL(trackID: track.id), track.container) }
            return (client.streamURL(trackID: track.id, choice: choice), choice.container(source: track.container))
        }
        downloads.mayFetch = { [weak self] in
            guard let self else { return false }
            if self.downloads.unmeteredOnly, self.streaming.network.isMetered || self.streaming.network.kind == .cellular { return false }
            if self.downloads.onlyWhileCharging, !PowerSource.onMains { return false }
            return !self.offline
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
        checkHome()
        // Where last session left off, paused: Play carries on.
        if rememberQueue, player.current == nil, let data = UserDefaults.standard.data(forKey: queueKey),
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
        if let partial = try? await continuo.partialAlbums() {
            partialAlbumIDs = Set(partial.keys)
        }
    }

    /// Library albums the library only partly has (continuo): Albums leaves them out unless asked.
    private(set) var partialAlbumIDs: Set<String> = []

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
        if case JellyfinError.http(status: 401) = error {
            notice = "This Jellyfin server signed the Mac out: add it again in Settings > Servers."
            return
        }
        notice = ErrorText.of(error)
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
                guard let self, let library = self.library else { return }
                if (try? await library.ping()) != nil {
                    self.unreachable = false
                    self.albumLists = [:]
                    DiagnosticLog.shared.log("offline", "back online")
                    return
                }
            }
        }
    }

    // MARK: Backup

    /// Settings > General > Backup: the settings and servers, as a file's bytes.
    func backup() async throws -> Data {
        let defaults = UserDefaults.standard.dictionaryRepresentation()
        let settings = defaults.compactMapValues { AppBackup.value($0) }
        return try AppBackup(settings: settings, servers: await store.load().servers).encoded()
    }

    /// Applies a backup. The servers come in without switching away from the one showing. What it
    /// says goes in `notice`; most settings are read at launch, so they take effect at the next one.
    func restore(_ data: Data) async throws {
        let backup = try AppBackup.decoded(data)
        let defaults = UserDefaults.standard
        for (key, value) in backup.settings {
            switch value {
            case let .bool(v): defaults.set(v, forKey: key)
            case let .int(v): defaults.set(v, forKey: key)
            case let .double(v): defaults.set(v, forKey: key)
            case let .string(v): defaults.set(v, forKey: key)
            case let .data(v): defaults.set(v, forKey: key)
            }
        }
        let (list, needSignIn) = backup.restored(into: await store.load())
        await store.save(list)
        servers = list.servers
        DiagnosticLog.shared.log("backup", "restored \(backup.settings.count) settings, \(backup.servers.count) servers")
        var message = "Restored. Quit and reopen Polyphony to apply the settings."
        if !needSignIn.isEmpty {
            let names = needSignIn.map { $0.address.host ?? $0.address.absoluteString }.joined(separator: ", ")
            message += " Sign in to \(names) again (Settings > Servers > Add Server)."
        }
        notice = message
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
                               libraryHost: s.libraryBaseURL.host)
        }
        let settings: [(String, String)] = [
            ("Streaming quality", "\(streaming.decision().profile.summary) (\(streaming.decision().explanation))"),
            ("Network", "\(streaming.network.kind.rawValue.lowercased())\(streaming.network.isMetered ? ", metered" : ""), home=\(streaming.atHome.map(String.init) ?? "unknown")"),
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
        guard let coverArtID, let library else { return nil }
        return library.coverArtURL(id: coverArtID, size: size)
    }

    func artworkURL(for entry: QueueEntry, size: Int) -> URL? {
        switch entry {
        case let .track(t): coverURL(t.coverArtID ?? t.albumID, size: size)
        case let .playNow(_, c), let .catalog(c): c.artworkURL
        }
    }

    private func source(for entry: QueueEntry) async -> AudioSource? {
        switch entry {
        case let .track(t):
            if speaker == nil {
                // A downloaded copy plays from disk: no network, original quality.
                if downloads.preferDownloaded || offline, let local = downloads.localURL(t.id) {
                    downloads.played(t.id)
                    return AudioSource(url: local)
                }
                let stream = macStream(t)
                if let cached = streamCache.file(stream.cacheName) { return AudioSource(url: cached) }
                if offlineMode { return nil }
                return library.map { AudioSource(url: streamURL(t, stream.choice, $0)) }
            }
            // A speaker fetches the audio itself, from an address it can reach, and shows the song: as
            // the rules say, in a format it says it plays.
            guard let library, let renderer = speaker else { return nil }
            let target = OutputTarget.dlna(udn: renderer.udn, name: renderer.name)
            let wanted = streaming.decision(for: target).profile
            let (profile, mime, substituted) = RendererCodecFallback.choose(wanted, container: t.container, formats: speakerFormats)
            if substituted { DiagnosticLog.shared.log("output", "\(renderer.name) doesn't take \(wanted.name): using \(profile.name)") }
            let url = streamURL(t, .forRenderer(profile), library)
            let art = coverURL(t.coverArtID ?? t.albumID, size: 1200).map(forSpeaker)
            return AudioSource(url: forSpeaker(url), info: .init(title: t.title, artist: t.artistName, album: t.albumName, artworkURL: art,
                                                                mimeType: mime, durationMs: Int(t.duration * 1000)))
        case let .playNow(stream, song):
            if speaker != nil {
                // A speaker sends no token: continuo signs it a link, on the LAN door, to the file as it is.
                guard let link = await playNowLink(stream.id) else { return nil }
                return AudioSource(url: link, info: .init(title: song.title, artist: song.artist, album: song.albumTitle,
                                                          mimeType: Self.mime(for: stream.format),
                                                          durationMs: stream.durationMs ?? song.durationMs))
            }
            guard let continuo, let token = try? await continuo.account.accessToken() else { return nil }
            return AudioSource(url: continuo.playNowAudioURL(id: stream.id), headers: ["Authorization": "Bearer \(token)"])
        case let .catalog(song):
            // Reached in the queue (or next up): the library's copy, else a Play now, one at a time.
            guard !offline, let found = await fetch(song) else { return nil }
            player.replace(entry, with: found)
            return await source(for: found)
        }
    }

    /// Where each catalogue song being fetched is ("Finding a copy…", "Downloading 40%"), by its key,
    /// for the queue and the player bar (the Android app's Play now statuses).
    private(set) var fetchStatus: [String: String] = [:]

    private static func fetchText(_ stream: PlayNowStream) -> String {
        switch stream.status {
        case "queued": "Waiting its turn"
        case "searching": "Finding a copy…"
        default: stream.percent > 0 ? "Downloading \(Int(stream.percent))%" : "Getting it…"
        }
    }

    /// Catalogue songs played from [index] on: each fetched as the queue reaches it.
    func playCatalog(_ songs: [CatalogTrack], from index: Int, title: String?) {
        player.play(entries: songs.map(QueueEntry.catalog), startAt: index, title: title)
    }

    /// Play Next / Add to Queue for a catalogue song, as the Android app says it.
    func queueCatalog(_ song: CatalogTrack, next: Bool) {
        if next { player.playNext([.catalog(song)]) } else { player.addToQueue([.catalog(song)]) }
        let fetched = song.availability.isInLibrary ? "" : "; fetching it from Soulseek"
        notice = next ? "\(song.title) plays next\(fetched)" : "Added \(song.title) to the queue\(fetched)"
    }

    /// A catalogue song as something the player can play: the library's copy when it's there, else a
    /// Play now continuo fetches (nil when it can't, said in a notice).
    private func fetch(_ song: CatalogTrack) async -> QueueEntry? {
        if case let .inLibrary(albumID?) = song.availability, let library,
           let album = try? await library.album(id: albumID),
           let track = album.tracks.first(where: { $0.title.caseInsensitiveCompare(song.title) == .orderedSame }) {
            return .track(track)
        }
        guard let continuo else { return nil }
        defer { fetchStatus[song.key] = nil }
        do {
            fetchStatus[song.key] = "Finding a copy…"
            var stream = try await continuo.startPlayNow(song, queued: true)
            for _ in 0..<180 where !stream.playable && !stream.failed {
                fetchStatus[song.key] = Self.fetchText(stream)
                try await Task.sleep(for: .seconds(1))
                stream = try await continuo.playNow(id: stream.id)
            }
            guard stream.playable, !stream.failed else {
                notice = stream.error ?? "Couldn't get \(song.title) right now."
                return nil
            }
            return .playNow(stream, song)
        } catch {
            handle(error)
            return nil
        }
    }

    // MARK: Catalogue actions

    /// Plays a song from the catalogue: from the library when it's there, else through Play now
    /// (continuo fetches it from Soulseek and streams it while it downloads).
    func play(_ track: CatalogTrack) async {
        if case let .inLibrary(albumID?) = track.availability, let library,
           let album = try? await library.album(id: albumID),
           let index = album.tracks.firstIndex(where: { $0.title.caseInsensitiveCompare(track.title) == .orderedSame }) {
            player.play(album.tracks, startAt: index, title: album.name)
            return
        }
        // Into the queue at once (the player bar says where it's at), fetched as it's reached.
        playCatalog([track], from: 0, title: track.title)
    }

    /// Whether continuo took it (or already had it): the page's button says Requested only then.
    @discardableResult
    func request(_ album: CatalogAlbum) async -> Bool {
        guard let continuo, let rg = album.releaseGroupID else { return false }
        do {
            switch try await continuo.requestAlbum(releaseGroupID: rg) {
            case .requested: notice = "Requested \(album.title). It'll appear in Requests."
            case .alreadyRequested: notice = "\(album.title) is already on its way."
            case .inLibrary: notice = "\(album.title) is already in your library."
            case .addedBack: notice = "\(album.title) is back in your library."
            }
            return true
        } catch {
            handle(error)
            return false
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
