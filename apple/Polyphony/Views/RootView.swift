import AppKit
import PolyphonyKit
import SwiftUI

enum SidebarItem: String, Hashable, CaseIterable, Identifiable {
    case home, search, artists, albums, songs, playlists, genres, favourites, downloads, requests, removed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "Home"
        case .search: "Search"
        case .artists: "Artists"
        case .albums: "Albums"
        case .songs: "Songs"
        case .playlists: "Playlists"
        case .genres: "Genres"
        case .favourites: "Favourites"
        case .downloads: "Downloads"
        case .requests: "Requests"
        case .removed: "Recently Removed"
        }
    }

    var symbol: String {
        switch self {
        case .home: "house"
        case .search: "magnifyingglass"
        case .artists: "music.mic"
        case .albums: "square.stack"
        case .songs: "music.note"
        case .playlists: "music.note.list"
        case .genres: "guitars"
        case .favourites: "heart"
        case .downloads: "arrow.down.to.line"
        case .requests: "arrow.down.circle"
        case .removed: "trash"
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .signedOut:
            SignInView()
        case .signedIn:
            MainWindow()
        }
    }
}

enum InspectorPane: String { case queue, lyrics }

struct MainWindow: View {
    @Environment(AppModel.self) private var model
    @State private var inspector: InspectorPane?
    @State private var keyMonitor: Any?

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            List(selection: $model.sidebar) {
                Section {
                    row(.home)
                    row(.search)
                }
                Section("Library") {
                    ForEach([SidebarItem.artists, .albums, .songs, .playlists, .genres, .favourites, .downloads]) { row($0) }
                }
                if model.isContinuo {
                    Section("Server") {
                        row(.requests)
                        row(.removed)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 200)
        } detail: {
            NavigationStack(path: $model.path) {
                detail(for: model.sidebar ?? .home)
                    .navigationDestination(for: Route.self) { route in destination(route) }
            }
        }
        .onChange(of: model.sidebar) { model.path = NavigationPath() }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            PlayerBar(inspector: $inspector)
        }
        .inspector(isPresented: Binding(get: { inspector != nil }, set: { if !$0 { inspector = nil } })) {
            Group {
                switch inspector {
                case .lyrics: LyricsView()
                default: QueueView()
                }
            }
            .inspectorColumnWidth(min: 260, ideal: 320, max: 460)
        }
        .overlay(alignment: .top) { NoticeBanner() }
        .onAppear { installSpaceToPlay() }
        .onDisappear { if let keyMonitor { NSEvent.removeMonitor(keyMonitor) } }
    }

    private func row(_ item: SidebarItem) -> some View {
        Label(item.title, systemImage: item.symbol).tag(item)
    }

    @ViewBuilder
    private func detail(for item: SidebarItem) -> some View {
        switch item {
        case .home: HomeView()
        case .search: SearchView()
        case .artists: ArtistsView()
        case .albums: AlbumsView()
        case .songs: SongsView()
        case .playlists: PlaylistsView()
        case .genres: GenresView()
        case .favourites: FavouritesView()
        case .downloads: DownloadsView()
        case .requests: RequestsView()
        case .removed: RemovedAlbumsView()
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case let .album(id): AlbumDetailView(albumID: id)
        case let .artist(id): ArtistDetailView(artistID: id)
        case let .playlist(id): PlaylistDetailView(playlistID: id)
        case let .genre(name): GenreDetailView(genre: name)
        case let .catalogAlbum(album): CatalogAlbumView(album: album)
        case let .catalogArtist(artist): CatalogArtistView(artist: artist)
        case let .discoveryPlaylist(playlist): DiscoveryPlaylistView(playlist: playlist)
        }
    }

    /// Space plays and pauses, as in Music, except while typing (a text field has the focus).
    private func installSpaceToPlay() {
        guard keyMonitor == nil else { return }
        let player = model.player
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            // Local monitors run on the main thread.
            MainActor.assumeIsolated { () -> NSEvent? in
                let typing = NSApp.keyWindow?.firstResponder is NSText
                guard event.keyCode == 49, event.modifierFlags.intersection(.deviceIndependentFlagsMask).isEmpty, !typing else {
                    return event
                }
                player.togglePlay()
                return nil
            }
        }
    }
}

/// A short message at the top ("Requested Parachutes"), gone after a few seconds.
struct NoticeBanner: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let notice = model.notice {
            Text(notice)
                .font(.callout)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.regularMaterial, in: Capsule())
                .shadow(radius: 4, y: 2)
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: notice) {
                    try? await Task.sleep(for: .seconds(4))
                    withAnimation { model.notice = nil }
                }
        }
    }
}
