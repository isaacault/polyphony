import PolyphonyKit
import SwiftUI

/// Home: what's new and what you've been playing in the library, then (with continuo) new releases
/// from your artists and discovery.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var recentlyAdded: [Album] = []
    @State private var recentlyPlayed: [Album] = []
    @State private var mostPlayed: [Album] = []
    @State private var random: [Album] = []
    @State private var favourites: [Album] = []
    @State private var discovery = Discovery()

    /// "Good morning", as the Android app's Home greets.
    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5...11: "Good morning"
        case 12...17: "Good afternoon"
        default: "Good evening"
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text(greeting).font(.largeTitle.weight(.bold))
                continueListening
                shelf("Recently Played", recentlyPlayed)
                shelf("Recently Added", recentlyAdded)
                if !discovery.forYou.isEmpty {
                    Section {
                        CatalogTrackList(tracks: Array(discovery.forYou.prefix(8)))
                    } header: { SectionTitle("For You", subtitle: "Songs you might like, from what you play") }
                }
                if !discovery.newReleases.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 18) {
                                ForEach(discovery.newReleases) { album in
                                    NavigationLink(value: catalogRoute(album)) { CatalogAlbumTile(album: album).frame(width: 160) }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                    } header: { SectionTitle("New Releases", subtitle: "From artists in your library") }
                }
                if !discovery.artists.isEmpty {
                    Section { CatalogArtistShelf(artists: discovery.artists) } header: {
                        SectionTitle("Artists for You", subtitle: "Like the ones you play")
                    }
                }
                if !discovery.albums.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 18) {
                                ForEach(discovery.albums) { album in
                                    NavigationLink(value: catalogRoute(album)) { CatalogAlbumTile(album: album).frame(width: 160) }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                    } header: { SectionTitle("Albums for You") }
                }
                if !discovery.playlists.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 14) {
                                ForEach(discovery.playlists) { playlist in
                                    NavigationLink(value: Route.discoveryPlaylist(playlist)) {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(playlist.title).font(.headline).lineLimit(2)
                                            if let d = playlist.description { Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(3) }
                                        }
                                        .padding(14)
                                        .frame(width: 200, height: 110, alignment: .topLeading)
                                        .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    } header: { SectionTitle("Playlists for You") }
                }
                shelf("Random Picks", random)
                shelf("Most Played", mostPlayed)
                shelf(model.usesRatings ? "Top Rated" : "Favourites", favourites)
                if !model.playlists.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 18) {
                                ForEach(model.playlists.prefix(15)) { playlist in
                                    NavigationLink(value: Route.playlist(playlist.id)) {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Artwork(url: model.coverURL(playlist.coverArtID, size: 400), symbol: "music.note.list")
                                            Text(playlist.name).font(.callout.weight(.medium)).lineLimit(1)
                                            Text("\(playlist.songCount) songs").font(.caption).foregroundStyle(.secondary)
                                        }
                                        .frame(width: 160)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    } header: { SectionTitle("Playlists") }
                }
                if recentlyAdded.isEmpty && recentlyPlayed.isEmpty && mostPlayed.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(60)
                }
            }
            .padding(24)
        }
        .navigationTitle("Home")
        .task { await load() }
        .refreshable { await load() }
    }

    /// Albums and playlists left part-way, each resumed where it stopped.
    @ViewBuilder
    private var continueListening: some View {
        let entries = Array(model.history.entries.filter { $0.trackID != nil }.prefix(12))
        if !entries.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 18) {
                        ForEach(entries, id: \.context.key) { entry in
                            Button { Task { await model.resume(entry) } } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Artwork(url: model.coverURL(entry.context.coverArtID, size: 400))
                                        .overlay(alignment: .bottomTrailing) {
                                            Image(systemName: "play.circle.fill").font(.title).symbolRenderingMode(.hierarchical)
                                                .padding(6)
                                        }
                                    Text(entry.context.title).font(.callout.weight(.medium)).lineLimit(1)
                                    Text("Song \(entry.trackIndex + 1)" + (entry.context.subtitle.map { " · \($0)" } ?? ""))
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                                .frame(width: 160)
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Start from the Beginning") {
                                    var fresh = entry
                                    fresh.trackID = nil
                                    Task { await model.resume(fresh) }
                                }
                                Button("Remove from Continue Listening") { model.forget(entry.context) }
                            }
                        }
                    }
                }
            } header: { SectionTitle("Continue Listening") }
        }
    }

    @ViewBuilder
    private func shelf(_ title: String, _ albums: [Album]) -> some View {
        // Single songs aren't albums: the shelves leave them out, as on Android.
        let shown = Array(albums.filter { !model.singleSongAlbumIDs.contains($0.id) }.prefix(20))
        if !shown.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 18) {
                        ForEach(shown) { album in
                            NavigationLink(value: Route.album(album.id)) { AlbumTile(album: album).frame(width: 160) }
                                .buttonStyle(.plain)
                                .albumContextMenu(album)
                        }
                    }
                }
            } header: { SectionTitle(title) }
        }
    }

    private func catalogRoute(_ album: CatalogAlbum) -> Route {
        if case let .inLibrary(id?) = album.availability { return .album(id) }
        return .catalogAlbum(album)
    }

    private func load() async {
        guard let subsonic = model.subsonic else { return }
        async let added = subsonic.albums(.newest, size: 30)
        async let played = subsonic.albums(.recent, size: 30)
        async let most = subsonic.albums(.frequent, size: 30)
        async let picks = subsonic.albums(.random, size: 30)
        async let liked = subsonic.albums(model.usesRatings ? .highest : .starred, size: 30)
        recentlyAdded = (try? await added) ?? []
        recentlyPlayed = (try? await played) ?? []
        mostPlayed = (try? await most) ?? []
        random = (try? await picks) ?? []
        favourites = (try? await liked) ?? []
        if let continuo = model.continuo {
            discovery = (try? await continuo.discovery()) ?? Discovery()
        }
    }
}

struct DiscoveryPlaylistView: View {
    @Environment(AppModel.self) private var model
    let playlist: DiscoveryPlaylist
    @State private var page: DiscoveryPlaylist?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(playlist.title).font(.largeTitle.weight(.bold))
                if let d = playlist.description { Text(d).foregroundStyle(.secondary) }
                if let page {
                    CatalogTrackList(tracks: page.tracks)
                } else {
                    ProgressView()
                }
            }
            .padding(24)
        }
        .navigationTitle(playlist.title)
        .task(id: playlist.id) { page = try? await model.continuo?.discoveryPlaylist(id: playlist.id) }
    }
}

/// continuo's notifications: arrivals, requests that need a look. Opening one goes to it.
struct InboxView: View {
    @Environment(AppModel.self) private var model
    @State private var items: [InboxNotification] = []
    @State private var loaded = false

    var body: some View {
        List(items) { item in
            Button { open(item) } label: {
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(item.read ? Color.clear : Color.accentColor).frame(width: 8, height: 8).padding(.top, 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.headline)
                        if let body = item.body, !body.isEmpty { Text(body).foregroundStyle(.secondary) }
                        if let at = item.createdAt { Text(String(at.prefix(16)).replacingOccurrences(of: "T", with: " ")).font(.caption).foregroundStyle(.tertiary) }
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .overlay { if loaded && items.isEmpty { Placeholder(title: "No notifications", symbol: "tray") } }
        .navigationTitle("Inbox")
        .toolbar {
            Button("Mark All Read") {
                Task {
                    await model.markRead(nil)
                    await load()
                }
            }
            .disabled(model.unreadCount == 0)
        }
        .task { await load() }
    }

    private func open(_ item: InboxNotification) {
        Task {
            if !item.read { await model.markRead([item.id]) }
            if let album = item.albumID {
                model.path.append(Route.album(album))
            } else if item.requestID != nil {
                model.go(to: .requests)
            }
            await load()
        }
    }

    private func load() async {
        do {
            items = try await model.continuo?.notifications().items ?? []
            loaded = true
        } catch {
            model.handle(error)
        }
    }
}

/// Albums taken out of the library, restorable for a while.
struct RemovedAlbumsView: View {
    @Environment(AppModel.self) private var model
    @State private var removed: [RemovedAlbum] = []
    @State private var loaded = false

    var body: some View {
        List(removed) { album in
            HStack {
                VStack(alignment: .leading) {
                    Text(album.title)
                    Text([album.artist, album.restorableUntil.map { "restorable until \(String($0.prefix(10)))" }]
                        .compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Restore") {
                    Task {
                        do {
                            try await model.continuo?.restoreAlbum(removedID: album.id)
                            model.albumLists = [:]
                            model.notice = "\(album.title) is back in your library."
                            await load()
                        } catch {
                            model.handle(error)
                        }
                    }
                }
            }
        }
        .overlay { if loaded && removed.isEmpty { Placeholder(title: "Nothing removed", symbol: "trash") } }
        .navigationTitle("Recently Removed")
        .task { await load() }
    }

    private func load() async {
        do {
            removed = try await model.continuo?.removedAlbums() ?? []
            loaded = true
        } catch {
            model.handle(error)
        }
    }
}

/// continuo's requests: where each album is (searching, downloading, importing…), and what to do
/// with one that needs a look.
struct RequestsView: View {
    @Environment(AppModel.self) private var model
    @State private var requests: [MusicRequest] = []
    @State private var loaded = false

    private var active: [MusicRequest] { requests.filter { !$0.isDone } }
    private var done: [MusicRequest] { requests.filter(\.isDone) }

    var body: some View {
        List {
            if !active.isEmpty {
                Section("On the Way") { ForEach(active) { RequestRow(request: $0, reload: load) } }
            }
            if !done.isEmpty {
                Section("Arrived") { ForEach(done) { RequestRow(request: $0, reload: load) } }
            }
        }
        .overlay {
            if loaded && requests.isEmpty {
                Placeholder(title: "No requests", symbol: "arrow.down.circle",
                            message: "Search for an album that isn't in your library and request it.")
            }
        }
        .navigationTitle("Requests")
        .task {
            // Requests move on by themselves: refresh while the page is open.
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(10))
            }
        }
    }

    private func load() async {
        guard let continuo = model.continuo else { return }
        do {
            requests = try await continuo.requests()
            loaded = true
        } catch {
            model.handle(error)
        }
    }
}

private struct RequestRow: View {
    @Environment(AppModel.self) private var model
    let request: MusicRequest
    let reload: () async -> Void

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: request.artworkURL, cornerRadius: 4).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(request.title).font(.headline).lineLimit(1)
                Text([request.artist, request.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                if !request.isDone {
                    Text(statusLine).font(.caption).foregroundStyle(request.needsLook ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary)).lineLimit(2)
                    if request.percent > 0 && request.percent < 100 {
                        ProgressView(value: Double(request.percent), total: 100).frame(maxWidth: 260)
                    }
                }
            }
            Spacer()
            if request.isDone, let albumID = request.albumID {
                NavigationLink(value: Route.album(albumID)) { Text("Open") }
            }
        }
        .padding(.vertical, 4)
        .contextMenu {
            if !request.isDone {
                Button("Search Again Now") { act(.searchNow) }
                if request.needsLook { Button("Retry") { act(.retry) } }
                Button("Try Another Download") { act(.dropDownload) }
            }
            Divider()
            Button(request.isDone ? "Clear" : "Cancel Request", role: .destructive) { act(.remove) }
        }
    }

    private var statusLine: String {
        if let error = request.error, request.needsLook { return error }
        if let attention = request.attention { return attention }
        if !request.detail.isEmpty { return request.detail }
        if request.stillLooking { return "Still looking" }
        return request.status.replacingOccurrences(of: "_", with: " ").capitalized
    }

    private func act(_ action: ContinuoService.RequestAction) {
        Task {
            do {
                let said = try await model.continuo?.perform(action, onRequest: request.id) ?? ""
                if !said.isEmpty { model.notice = said }
                await reload()
            } catch {
                model.handle(error)
            }
        }
    }
}
