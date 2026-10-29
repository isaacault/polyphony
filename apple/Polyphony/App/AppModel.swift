import Foundation
import Observation
import PolyphonyKit

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
    /// A Play now song being fetched before it can play.
    private(set) var pendingPlayNow: PlayNowStream?

    let player: PlayerController
    @ObservationIgnored private let nowPlaying = NowPlayingCenter()
    @ObservationIgnored private let store = KeychainServerStore()

    init() {
        player = PlayerController(engine: AVPlayerEngine(), nowPlaying: nowPlaying)
        nowPlaying.attach(player)
        player.resolve = { [weak self] entry in await self?.source(for: entry) }
        player.artwork = { [weak self] entry in self?.artworkURL(for: entry, size: 600) }
        player.scrobble = { [weak self] track, submission in
            guard let subsonic = self?.subsonic else { return }
            Task { try? await subsonic.scrobble(trackID: track.id, submission: submission) }
        }
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
        player.stop()
        if let account = continuo?.account { await account.signOut() }
        await store.save(nil)
        server = nil
        subsonic = nil
        continuo = nil
        singleSongAlbumIDs = []
        state = .signedOut
    }

    private func attach(_ saved: SavedServer) async {
        server = saved
        subsonic = SubsonicClient(credentials: saved.subsonic)
        if saved.kind == .continuo {
            let account = ContinuoAccount(client: ContinuoClient(baseURL: saved.address),
                                          store: ServerSessionStore(store))
            _ = await account.restore()
            continuo = ContinuoService(account: account)
        } else {
            continuo = nil
        }
        state = .signedIn
        await refreshSingles()
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
            return subsonic.map { AudioSource(url: $0.streamURL(trackID: t.id)) }
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
