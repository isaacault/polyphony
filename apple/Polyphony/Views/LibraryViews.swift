import PolyphonyKit
import SwiftUI

// MARK: Albums

struct AlbumsView: View {
    @Environment(AppModel.self) private var model
    @State private var albums: [Album] = []
    @State private var sort: SubsonicClient.AlbumListType = .alphabeticalByName
    @State private var showSingles = false
    @State private var loading = true
    @State private var filter = ""

    /// Full albums by default; albums where you have just one song are under "Single songs".
    private var shown: [Album] {
        let singles = model.singleSongAlbumIDs
        let picked = albums.filter { showSingles == singles.contains($0.id) }
        guard !filter.isEmpty else { return picked }
        return picked.filter { $0.name.localizedCaseInsensitiveContains(filter) || ($0.artistName ?? "").localizedCaseInsensitiveContains(filter) }
    }

    var body: some View {
        ScrollView {
            if loading && albums.isEmpty {
                ProgressView().padding(60)
            } else if shown.isEmpty {
                Placeholder(title: showSingles ? "No single songs" : "No albums", symbol: "square.stack")
            } else {
                AlbumGrid(albums: shown).padding(20)
            }
        }
        .navigationTitle(showSingles ? "Single Songs" : "Albums")
        .searchable(text: $filter, placement: .toolbar, prompt: "Filter albums")
        .toolbar {
            if !model.singleSongAlbumIDs.isEmpty {
                Picker("Show", selection: $showSingles) {
                    Text("Albums").tag(false)
                    Text("Single Songs").tag(true)
                }
                .pickerStyle(.segmented)
            }
            Picker("Sort", selection: $sort) {
                Text("Name").tag(SubsonicClient.AlbumListType.alphabeticalByName)
                Text("Artist").tag(SubsonicClient.AlbumListType.alphabeticalByArtist)
                Text("Recently Added").tag(SubsonicClient.AlbumListType.newest)
                Text("Recently Played").tag(SubsonicClient.AlbumListType.recent)
                Text("Most Played").tag(SubsonicClient.AlbumListType.frequent)
            }
        }
        .task(id: sort) { await load() }
    }

    private func load() async {
        guard let subsonic = model.subsonic else { return }
        if let cached = model.albumLists[sort] {
            albums = cached
        }
        loading = albums.isEmpty
        defer { loading = false }
        var all: [Album] = []
        do {
            // Pages of 500 until the library runs out (most libraries are a few pages).
            for offset in stride(from: 0, to: 20_000, by: 500) {
                let page = try await subsonic.albums(sort, size: 500, offset: offset)
                all += page
                if offset == 0 && albums.isEmpty { albums = all }
                if page.count < 500 { break }
            }
            albums = all
            model.albumLists[sort] = all
        } catch {
            model.handle(error)
        }
    }
}

struct AlbumDetailView: View {
    @Environment(AppModel.self) private var model
    let albumID: String
    @State private var album: Album?
    @State private var error: String?

    var body: some View {
        Group {
            if let album {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        AlbumHeader(album: album)
                        TrackList(tracks: album.tracks, numbered: true, albumBadge: AudioQuality.albumBadge(album.tracks))
                    }
                    .padding(24)
                }
            } else if let error {
                Placeholder(title: "Couldn't open the album", symbol: "exclamationmark.triangle", message: error)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(album?.name ?? "")
        .task(id: albumID) {
            do {
                album = try await model.subsonic?.album(id: albumID)
            } catch {
                self.error = (error as? CustomStringConvertible)?.description ?? error.localizedDescription
            }
        }
    }
}

private struct AlbumHeader: View {
    @Environment(AppModel.self) private var model
    @State var album: Album

    var body: some View {
        HStack(alignment: .bottom, spacing: 22) {
            Artwork(url: model.coverURL(album.coverArtID ?? album.id, size: 800), cornerRadius: 10)
                .frame(width: 220, height: 220)
                .shadow(radius: 8, y: 4)
            VStack(alignment: .leading, spacing: 6) {
                Text(album.name).font(.largeTitle.weight(.bold)).lineLimit(3)
                if let artistID = album.artistID {
                    NavigationLink(value: Route.artist(artistID)) {
                        Text(album.artistName ?? "").font(.title2).foregroundStyle(.tint)
                    }
                    .buttonStyle(.plain)
                } else {
                    Text(album.artistName ?? "").font(.title2)
                }
                HStack(spacing: 6) {
                    Text([album.genre, album.year.map(String.init), "\(album.songCount) songs", formatLong(album.duration)]
                        .compactMap { $0 }.joined(separator: " · "))
                    if let badge = AudioQuality.albumBadge(album.tracks) { QualityChip(label: badge) }
                }
                .font(.callout).foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    Button { model.player.play(album.tracks, title: album.name) } label: { Label("Play", systemImage: "play.fill") }
                        .buttonStyle(.borderedProminent)
                    Button { model.player.play(album.tracks, shuffle: true, title: album.name) } label: { Label("Shuffle", systemImage: "shuffle") }
                    Button {
                        Task {
                            try? await model.subsonic?.setStarred(.album(album.id), !album.starred)
                            album.starred.toggle()
                        }
                    } label: { Image(systemName: album.starred ? "heart.fill" : "heart") }
                        .help(album.starred ? "Remove from Favourites" : "Add to Favourites")
                    Menu {
                        Button("Play Next") { model.player.playNext(album.tracks.map(QueueEntry.track)) }
                        Button("Add to Queue") { model.player.addToQueue(album.tracks.map(QueueEntry.track)) }
                        AddToPlaylistMenu(trackIDs: album.tracks.map(\.id))
                    } label: { Image(systemName: "ellipsis") }
                        .menuIndicator(.hidden)
                        .fixedSize()
                }
                .controlSize(.large)
                .padding(.top, 6)
            }
        }
    }
}

/// Songs as a list: number (or artwork), title with its quality chip, artist, time. Double-click plays
/// from there; the whole list is the queue.
struct TrackList: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]
    var numbered = false
    var showArtwork = false
    var albumBadge: String?
    /// A playlist's songs can be taken out of it (by position: the same song can be there twice).
    var onRemove: ((Int) -> Void)?

    var body: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
                TrackRow(track: track, index: index, numbered: numbered, showArtwork: showArtwork, albumBadge: albumBadge,
                         isCurrent: model.player.current?.libraryTrack?.id == track.id)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) { model.player.play(tracks, startAt: index) }
                    .trackContextMenu(track, in: tracks, onRemove: onRemove.map { remove in { remove(index) } })
                Divider().opacity(0.4)
            }
        }
    }
}

private struct TrackRow: View {
    @Environment(AppModel.self) private var model
    let track: Track
    let index: Int
    let numbered: Bool
    let showArtwork: Bool
    let albumBadge: String?
    let isCurrent: Bool
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            if showArtwork {
                Artwork(url: model.coverURL(track.coverArtID ?? track.albumID, size: 80), cornerRadius: 3).frame(width: 34, height: 34)
            } else {
                Group {
                    if isCurrent {
                        Image(systemName: model.player.isPlaying ? "speaker.wave.2.fill" : "speaker.fill").foregroundStyle(.tint)
                    } else if hovering {
                        Button { model.player.play([track]) } label: { Image(systemName: "play.fill") }.buttonStyle(.plain)
                    } else {
                        Text(numbered ? "\(track.trackNumber ?? index + 1)" : "\(index + 1)").foregroundStyle(.secondary)
                    }
                }
                .frame(width: 26, alignment: .trailing)
                .monospacedDigit()
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(track.title).lineLimit(1).foregroundStyle(isCurrent ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    if let chip = AudioQuality.trackChip(track, albumBadge: albumBadge) { QualityChip(label: chip) }
                }
                if !numbered || showArtwork {
                    Text([track.artistName, track.albumName].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if track.starred { Image(systemName: "heart.fill").font(.caption).foregroundStyle(.secondary) }
            Text(formatDuration(track.duration)).foregroundStyle(.secondary).monospacedDigit()
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 8)
        .background(hovering ? Color.primary.opacity(0.05) : .clear, in: RoundedRectangle(cornerRadius: 5))
        .onHover { hovering = $0 }
    }
}

// MARK: Artists

struct ArtistsView: View {
    @Environment(AppModel.self) private var model
    @State private var artists: [Artist] = []
    @State private var filter = ""
    @State private var loading = true

    private var shown: [Artist] {
        filter.isEmpty ? artists : artists.filter { $0.name.localizedCaseInsensitiveContains(filter) }
    }

    var body: some View {
        List(shown) { artist in
            NavigationLink(value: Route.artist(artist.id)) {
                HStack(spacing: 10) {
                    Artwork(url: model.coverURL(artist.coverArtID ?? artist.id, size: 100), cornerRadius: 20, symbol: "music.mic")
                        .frame(width: 40, height: 40)
                    VStack(alignment: .leading) {
                        Text(artist.name)
                        Text(artist.albumCount == 1 ? "1 album" : "\(artist.albumCount) albums").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .overlay { if loading && artists.isEmpty { ProgressView() } }
        .navigationTitle("Artists")
        .searchable(text: $filter, placement: .toolbar, prompt: "Filter artists")
        .task {
            if let cached = model.artistList { artists = cached }
            defer { loading = false }
            do {
                artists = try await model.subsonic?.artists() ?? []
                model.artistList = artists
            } catch {
                model.handle(error)
            }
        }
    }
}

struct ArtistDetailView: View {
    @Environment(AppModel.self) private var model
    let artistID: String
    @State private var artist: Artist?
    @State private var albums: [Album] = []
    @State private var info: ArtistInfo?
    @State private var catalog: CatalogArtistPage?
    @State private var discography: [CatalogAlbum] = []

    /// The catalogue's albums that aren't in the library (continuo), to request.
    private var missing: [CatalogAlbum] {
        discography.filter { !$0.availability.isInLibrary }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .bottom, spacing: 20) {
                    Artwork(url: info?.imageURL ?? model.coverURL(artist?.coverArtID ?? artistID, size: 600), cornerRadius: 110, symbol: "music.mic")
                        .frame(width: 180, height: 180)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(artist?.name ?? "").font(.largeTitle.weight(.bold))
                        HStack {
                            Button { Task { await playAll(shuffle: false) } } label: { Label("Play", systemImage: "play.fill") }
                                .buttonStyle(.borderedProminent)
                            Button { Task { await playAll(shuffle: true) } } label: { Label("Shuffle", systemImage: "shuffle") }
                        }
                        .controlSize(.large)
                    }
                }
                if let top = catalog?.topTracks, !top.isEmpty {
                    Section {
                        CatalogTrackList(tracks: Array(top.prefix(10)))
                    } header: { SectionTitle("Top Songs") }
                }
                if !albums.isEmpty {
                    Section {
                        AlbumGrid(albums: albums.filter { !model.singleSongAlbumIDs.contains($0.id) })
                    } header: { SectionTitle("Albums") }
                }
                if !missing.isEmpty {
                    Section {
                        CatalogAlbumGrid(albums: missing)
                    } header: { SectionTitle("More by \(artist?.name ?? "them")", subtitle: "Not in your library: click one to request it") }
                }
                if let bio = catalog?.biography ?? info?.biography, !bio.isEmpty {
                    Section {
                        Text(stripHTML(bio)).foregroundStyle(.secondary).textSelection(.enabled).frame(maxWidth: 700, alignment: .leading)
                    } header: { SectionTitle("About") }
                }
                if let similar = info?.similarArtists, !similar.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 16) {
                                ForEach(similar) { a in
                                    NavigationLink(value: Route.artist(a.id)) {
                                        VStack {
                                            Artwork(url: model.coverURL(a.coverArtID ?? a.id, size: 200), cornerRadius: 50, symbol: "music.mic")
                                                .frame(width: 100, height: 100)
                                            Text(a.name).font(.caption).lineLimit(1).frame(width: 100)
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    } header: { SectionTitle("Similar Artists") }
                }
            }
            .padding(24)
        }
        .navigationTitle(artist?.name ?? "")
        .task(id: artistID) { await load() }
    }

    private func load() async {
        guard let subsonic = model.subsonic else { return }
        do {
            let (a, list) = try await subsonic.artist(id: artistID)
            artist = a
            albums = list.sorted { ($0.year ?? 0) > ($1.year ?? 0) }
        } catch {
            model.handle(error)
            return
        }
        info = try? await subsonic.artistInfo(id: artistID)
        guard let continuo = model.continuo, let name = artist?.name else { return }
        // The catalogue artist of the same name: its top songs and what isn't in the library yet.
        if let hit = try? await continuo.search(name, limit: 5).artists.first(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }),
           let mbid = hit.musicBrainzID {
            catalog = try? await continuo.catalogArtist(musicBrainzID: mbid)
            discography = (try? await continuo.catalogArtistAlbums(musicBrainzID: mbid)) ?? []
        }
    }

    private func playAll(shuffle: Bool) async {
        guard let subsonic = model.subsonic else { return }
        var tracks: [Track] = []
        for album in albums {
            if let full = try? await subsonic.album(id: album.id) { tracks += full.tracks }
        }
        model.player.play(tracks, shuffle: shuffle, title: artist?.name)
    }
}

struct SectionTitle: View {
    let title: String
    var subtitle: String?

    init(_ title: String, subtitle: String? = nil) {
        self.title = title
        self.subtitle = subtitle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.title2.weight(.semibold))
            if let subtitle { Text(subtitle).font(.callout).foregroundStyle(.secondary) }
        }
    }
}

func stripHTML(_ text: String) -> String {
    text.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"")
        .replacingOccurrences(of: "&#39;", with: "'").trimmingCharacters(in: .whitespacesAndNewlines)
}

// MARK: Songs

struct SongsView: View {
    @Environment(AppModel.self) private var model
    @State private var tracks: [Track] = []
    @State private var exhausted = false
    @State private var loading = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading) {
                HStack {
                    Button { model.player.play(tracks, shuffle: true, title: "Songs") } label: { Label("Shuffle All", systemImage: "shuffle") }
                        .disabled(tracks.isEmpty)
                }
                TrackList(tracks: tracks, showArtwork: true)
                if !exhausted {
                    ProgressView().padding().onAppear { Task { await more() } }
                }
            }
            .padding(20)
        }
        .navigationTitle("Songs")
        .task { if tracks.isEmpty { await more() } }
    }

    /// The next page: Navidrome lists every song for an empty search.
    private func more() async {
        guard !loading, !exhausted, let subsonic = model.subsonic else { return }
        loading = true
        defer { loading = false }
        do {
            let page = try await subsonic.search("", artists: 0, albums: 0, tracks: 200, trackOffset: tracks.count).tracks
            tracks += page
            if page.count < 200 { exhausted = true }
        } catch {
            exhausted = true
            model.handle(error)
        }
    }
}

// MARK: Playlists

struct PlaylistsView: View {
    @Environment(AppModel.self) private var model
    @State private var playlists: [Playlist] = []
    @State private var naming = false
    @State private var newName = ""

    var body: some View {
        List(playlists) { playlist in
            NavigationLink(value: Route.playlist(playlist.id)) {
                HStack(spacing: 10) {
                    Artwork(url: model.coverURL(playlist.coverArtID, size: 100), cornerRadius: 4, symbol: "music.note.list")
                        .frame(width: 40, height: 40)
                    VStack(alignment: .leading) {
                        Text(playlist.name)
                        Text("\(playlist.songCount) songs · \(formatLong(playlist.duration))").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .contextMenu {
                Button("Delete Playlist", role: .destructive) {
                    Task {
                        try? await model.subsonic?.deletePlaylist(id: playlist.id)
                        await load()
                    }
                }
            }
        }
        .navigationTitle("Playlists")
        .toolbar {
            Button { naming = true } label: { Label("New Playlist", systemImage: "plus") }
        }
        .alert("New Playlist", isPresented: $naming) {
            TextField("Name", text: $newName)
            Button("Create") {
                let name = newName
                newName = ""
                Task {
                    try? await model.subsonic?.createPlaylist(name: name)
                    await load()
                }
            }
            Button("Cancel", role: .cancel) { newName = "" }
        }
        .task { await load() }
    }

    private func load() async {
        do {
            playlists = try await model.subsonic?.playlists() ?? []
        } catch {
            model.handle(error)
        }
    }
}

struct PlaylistDetailView: View {
    @Environment(AppModel.self) private var model
    let playlistID: String
    @State private var playlist: Playlist?

    var body: some View {
        ScrollView {
            if let playlist {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(alignment: .bottom, spacing: 20) {
                        Artwork(url: model.coverURL(playlist.coverArtID, size: 600), cornerRadius: 10, symbol: "music.note.list")
                            .frame(width: 200, height: 200)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(playlist.name).font(.largeTitle.weight(.bold))
                            if let comment = playlist.comment, !comment.isEmpty { Text(comment).foregroundStyle(.secondary) }
                            Text("\(playlist.songCount) songs · \(formatLong(playlist.duration))").foregroundStyle(.secondary)
                            HStack {
                                Button { model.player.play(playlist.tracks, title: playlist.name) } label: { Label("Play", systemImage: "play.fill") }
                                    .buttonStyle(.borderedProminent)
                                Button { model.player.play(playlist.tracks, shuffle: true, title: playlist.name) } label: {
                                    Label("Shuffle", systemImage: "shuffle")
                                }
                            }
                            .controlSize(.large)
                        }
                    }
                    TrackList(tracks: playlist.tracks, showArtwork: true, onRemove: { index in
                        Task {
                            try? await model.subsonic?.updatePlaylist(id: playlist.id, removingIndices: [index])
                            self.playlist = try? await model.subsonic?.playlist(id: playlistID)
                        }
                    })
                }
                .padding(24)
            } else {
                ProgressView().padding(60)
            }
        }
        .navigationTitle(playlist?.name ?? "")
        .task(id: playlistID) {
            do {
                playlist = try await model.subsonic?.playlist(id: playlistID)
            } catch {
                model.handle(error)
            }
        }
    }
}

// MARK: Genres

struct GenresView: View {
    @Environment(AppModel.self) private var model
    @State private var genres: [Genre] = []

    var body: some View {
        List(genres) { genre in
            NavigationLink(value: Route.genre(genre.name)) {
                HStack {
                    Text(genre.name)
                    Spacer()
                    Text("\(genre.albumCount) albums").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Genres")
        .task {
            do {
                genres = try await model.subsonic?.genres() ?? []
            } catch {
                model.handle(error)
            }
        }
    }
}

struct GenreDetailView: View {
    @Environment(AppModel.self) private var model
    let genre: String
    @State private var albums: [Album] = []

    var body: some View {
        ScrollView {
            AlbumGrid(albums: albums).padding(20)
        }
        .navigationTitle(genre)
        .toolbar {
            Button {
                Task {
                    if let songs = try? await model.subsonic?.songs(.genre(genre), size: 300) {
                        model.player.play(songs, shuffle: true, title: genre)
                    }
                }
            } label: { Label("Shuffle Genre", systemImage: "shuffle") }
        }
        .task(id: genre) {
            albums = (try? await model.subsonic?.albums(genre: genre, size: 500)) ?? []
        }
    }
}

// MARK: Favourites

struct FavouritesView: View {
    @Environment(AppModel.self) private var model
    @State private var starred = Starred()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if !starred.tracks.isEmpty {
                    Section {
                        TrackList(tracks: starred.tracks, showArtwork: true)
                    } header: {
                        HStack {
                            SectionTitle("Songs")
                            Spacer()
                            Button { model.player.play(starred.tracks, shuffle: true, title: "Favourites") } label: {
                                Label("Shuffle", systemImage: "shuffle")
                            }
                        }
                    }
                }
                if !starred.albums.isEmpty {
                    Section { AlbumGrid(albums: starred.albums) } header: { SectionTitle("Albums") }
                }
                if starred.tracks.isEmpty && starred.albums.isEmpty {
                    Placeholder(title: "No favourites yet", symbol: "heart",
                                message: "Songs and albums you favourite show up here.")
                }
            }
            .padding(24)
        }
        .navigationTitle("Favourites")
        .task {
            do {
                starred = try await model.subsonic?.starred() ?? Starred()
            } catch {
                model.handle(error)
            }
        }
    }
}
