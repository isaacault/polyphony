import PolyphonyKit
import SwiftUI

/// Search: with continuo, the library and the catalogue together, ranked by what people listen to,
/// with continuo's lead hit first; anything not in the library can be requested or played now.
/// Without continuo, the library's own search.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var results = CatalogResults()
    @State private var library = SearchResults()
    @State private var searching = false
    @State private var recent = RecentSearches(UserDefaults.standard.stringArray(forKey: "search.recent") ?? [])

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                if query.isEmpty, !recent.items.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 2) {
                            ForEach(recent.items, id: \.self) { item in
                                HStack {
                                    Button { query = item } label: {
                                        Label(item, systemImage: "clock.arrow.circlepath").frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    .buttonStyle(.plain)
                                    Button { remember { $0.remove(item) } } label: { Image(systemName: "xmark") }
                                        .buttonStyle(.borderless).foregroundStyle(.secondary).help("Remove")
                                }
                                .padding(.vertical, 5)
                            }
                        }
                        .frame(maxWidth: 420, alignment: .leading)
                    } header: {
                        HStack {
                            SectionTitle("Recent Searches")
                            Button("Clear") { remember { $0.clear() } }.buttonStyle(.link)
                        }
                    }
                } else if query.isEmpty {
                    Placeholder(title: "Search", symbol: "magnifyingglass",
                                message: model.isContinuo ? "Your library and everything else, ranked by what people listen to."
                                                         : "Search your library.")
                        .padding(.top, 60)
                } else if model.offline {
                    // No server: what's on this Mac.
                    let found = model.downloads.index.search(query)
                    if found.isEmpty {
                        Placeholder(title: "Nothing downloaded matches", symbol: "wifi.slash",
                                    message: "You're offline: search looks through your downloads only.")
                            .padding(.top, 60)
                    } else {
                        Section { TrackList(tracks: found, showArtwork: true) } header: {
                            SectionTitle("Downloaded", subtitle: "You're offline: these are on this Mac")
                        }
                    }
                } else if model.isContinuo {
                    catalogResults
                } else {
                    libraryResults
                }
            }
            .padding(24)
        }
        .navigationTitle("Search")
        .searchable(text: $query, placement: .toolbar, prompt: model.isContinuo ? "Artists, albums, songs" : "Your library")
        .onSubmit(of: .search) { remember { $0.add(query) } }
        .task(id: query) { await search() }
        .overlay(alignment: .topTrailing) { if searching { ProgressView().controlSize(.small).padding() } }
    }

    @ViewBuilder
    private var catalogResults: some View {
        if let best = results.best {
            Section { TopHitCard(hit: best) } header: { SectionTitle("Top Result") }
        }
        if !results.tracks.isEmpty {
            Section { CatalogTrackList(tracks: Array(results.tracks.prefix(12))) } header: { SectionTitle("Songs") }
        }
        if !results.albums.isEmpty {
            Section { CatalogAlbumGrid(albums: results.albums) } header: { SectionTitle("Albums") }
        }
        if !results.artists.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 18) {
                        ForEach(results.artists) { artist in
                            NavigationLink(value: Route.catalogArtist(artist)) {
                                VStack {
                                    Artwork(url: artist.artworkURL, cornerRadius: 60, symbol: "music.mic").frame(width: 120, height: 120)
                                    Text(artist.name).font(.callout).lineLimit(1).frame(width: 120)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            } header: { SectionTitle("Artists") }
        }
        if !results.catalogAvailable {
            Text("The catalogue isn't answering right now: these are from your library only.")
                .font(.callout).foregroundStyle(.secondary)
        }
        if results.tracks.isEmpty && results.albums.isEmpty && results.artists.isEmpty && !searching {
            Placeholder(title: "No results", symbol: "magnifyingglass", message: "Nothing matches “\(query)”.")
        }
    }

    @ViewBuilder
    private var libraryResults: some View {
        if !library.tracks.isEmpty {
            Section { TrackList(tracks: library.tracks, showArtwork: true) } header: { SectionTitle("Songs") }
        }
        if !library.albums.isEmpty {
            Section { AlbumGrid(albums: library.albums) } header: { SectionTitle("Albums") }
        }
        if !library.artists.isEmpty {
            Section {
                ForEach(library.artists) { artist in
                    NavigationLink(artist.name, value: Route.artist(artist.id))
                }
            } header: { SectionTitle("Artists") }
        }
    }

    private func remember(_ change: (inout RecentSearches) -> Void) {
        change(&recent)
        UserDefaults.standard.set(recent.items, forKey: "search.recent")
    }

    private func search() async {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else {
            results = CatalogResults()
            library = SearchResults()
            return
        }
        // Debounce typing: a newer keystroke cancels this task before it asks.
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled, !model.offline else { return }
        searching = true
        defer { searching = false }
        do {
            if let continuo = model.continuo {
                // A quick answer first, then the full ranking.
                let quick = try await continuo.search(text, limit: 10, quick: true)
                guard !Task.isCancelled else { return }
                results = quick
                let full = try await continuo.search(text)
                guard !Task.isCancelled else { return }
                results = full
            } else if let subsonic = model.subsonic {
                library = try await subsonic.search(text)
            }
            // A query left standing a moment (not one on the way to another) is remembered.
            Task {
                try? await Task.sleep(for: .seconds(2))
                if query.trimmingCharacters(in: .whitespaces) == text { remember { $0.add(text) } }
            }
        } catch is CancellationError {
        } catch {
            if !Task.isCancelled { model.handle(error) }
        }
    }
}

private struct TopHitCard: View {
    @Environment(AppModel.self) private var model
    let hit: CatalogResults.TopHit

    var body: some View {
        switch hit {
        case let .album(album):
            NavigationLink(value: route(album)) {
                card(art: album.artworkURL, title: album.title, subtitle: ["Album", album.artist, album.year.map(String.init)])
            }
            .buttonStyle(.plain)
        case let .artist(artist):
            NavigationLink(value: Route.catalogArtist(artist)) {
                card(art: artist.artworkURL, title: artist.name, subtitle: ["Artist"], round: true)
            }
            .buttonStyle(.plain)
        case let .track(track):
            Button { Task { await model.play(track) } } label: {
                card(art: track.artworkURL, title: track.title, subtitle: ["Song", track.artist])
            }
            .buttonStyle(.plain)
        }
    }

    private func route(_ album: CatalogAlbum) -> Route {
        if case let .inLibrary(id?) = album.availability { return .album(id) }
        return .catalogAlbum(album)
    }

    private func card(art: URL?, title: String, subtitle: [String?], round: Bool = false) -> some View {
        HStack(spacing: 16) {
            Artwork(url: art, cornerRadius: round ? 50 : 8).frame(width: 100, height: 100)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.title.weight(.bold)).lineLimit(2)
                Text(subtitle.compactMap { $0 }.joined(separator: " · ")).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: 520)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Catalogue songs: the library's copy plays at once; others play now (continuo fetches them) or can
/// be requested.
struct CatalogTrackList: View {
    @Environment(AppModel.self) private var model
    let tracks: [CatalogTrack]

    var body: some View {
        LazyVStack(spacing: 0) {
            ForEach(tracks) { track in
                HStack(spacing: 12) {
                    Artwork(url: track.artworkURL, cornerRadius: 3).frame(width: 34, height: 34)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).lineLimit(1)
                        Text([track.artist, track.albumTitle].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    if model.pendingPlayNow?.title == track.title {
                        ProgressView().controlSize(.small)
                        Text(playNowStatus).font(.caption).foregroundStyle(.secondary)
                    } else if !track.availability.isInLibrary {
                        Text("Not in library").font(.caption).foregroundStyle(.secondary)
                    }
                    Text(formatDuration(TimeInterval(track.durationMs ?? 0) / 1000)).foregroundStyle(.secondary).monospacedDigit()
                }
                .padding(.vertical, 6).padding(.horizontal, 8)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) { Task { await model.play(track) } }
                .contextMenu {
                    Button(track.availability.isInLibrary ? "Play" : "Play Now") { Task { await model.play(track) } }
                    if !track.availability.isInLibrary, track.recordingID != nil {
                        Button("Request Song") { Task { await model.request(track) } }
                    }
                }
                Divider().opacity(0.4)
            }
        }
    }

    private var playNowStatus: String {
        guard let s = model.pendingPlayNow else { return "" }
        return s.status == "searching" ? "Finding it…" : "Getting it… \(Int(s.percent))%"
    }
}

struct CatalogAlbumGrid: View {
    let albums: [CatalogAlbum]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 18)], alignment: .leading, spacing: 22) {
            ForEach(albums) { album in
                NavigationLink(value: route(album)) { CatalogAlbumTile(album: album) }
                    .buttonStyle(.plain)
            }
        }
    }

    private func route(_ album: CatalogAlbum) -> Route {
        if case let .inLibrary(id?) = album.availability { return .album(id) }
        return .catalogAlbum(album)
    }
}

/// An album that isn't (all) in the library: its tracklist from the catalogue, and the request button.
struct CatalogAlbumView: View {
    @Environment(AppModel.self) private var model
    let album: CatalogAlbum
    @State private var page: CatalogAlbumPage?
    @State private var requested = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .bottom, spacing: 22) {
                    Artwork(url: album.artworkURL, cornerRadius: 10).frame(width: 220, height: 220).shadow(radius: 8, y: 4)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(album.title).font(.largeTitle.weight(.bold)).lineLimit(3)
                        Text(album.artist ?? "").font(.title2)
                        Text([album.albumType?.capitalized, album.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                            .foregroundStyle(.secondary)
                        requestButton.padding(.top, 6)
                    }
                }
                if let page {
                    LazyVStack(spacing: 0) {
                        ForEach(page.tracks, id: \.self) { entry in
                            HStack {
                                Text("\(entry.position)").foregroundStyle(.secondary).frame(width: 26, alignment: .trailing).monospacedDigit()
                                Text(entry.title)
                                Spacer()
                                Text(formatDuration(TimeInterval(entry.durationMs ?? 0) / 1000)).foregroundStyle(.secondary).monospacedDigit()
                            }
                            .padding(.vertical, 6).padding(.horizontal, 8)
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) { Task { await playNow(entry) } }
                            .contextMenu {
                                Button("Play Now") { Task { await playNow(entry) } }
                            }
                            Divider().opacity(0.4)
                        }
                    }
                } else {
                    ProgressView()
                }
            }
            .padding(24)
        }
        .navigationTitle(album.title)
        .task(id: album.key) {
            if let rg = album.releaseGroupID { page = try? await model.continuo?.catalogAlbum(releaseGroupID: rg) }
        }
    }

    @ViewBuilder
    private var requestButton: some View {
        switch album.availability {
        case let .requested(_, state, _):
            Label(state.replacingOccurrences(of: "_", with: " ").capitalized, systemImage: "arrow.down.circle")
                .foregroundStyle(.secondary)
        case let .partial(have, expected, _, requestable):
            HStack {
                Text("You have \(have) of \(expected) songs.").foregroundStyle(.secondary)
                if requestable { request("Request the Rest") }
            }
        case .requestable, .onServer:
            request(requested ? "Requested" : "Request Album")
        default:
            EmptyView()
        }
    }

    private func request(_ title: String) -> some View {
        Button {
            Task { requested = await model.request(album) }
        } label: { Label(title, systemImage: requested ? "checkmark" : "plus") }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(requested)
    }

    private func playNow(_ entry: CatalogTracklistEntry) async {
        let track = CatalogTrackBuilder.make(entry, album: album)
        await model.play(track)
    }
}

/// A catalogue artist: their top songs and discography, with what's in the library marked.
struct CatalogArtistView: View {
    @Environment(AppModel.self) private var model
    let artist: CatalogArtist
    @State private var page: CatalogArtistPage?
    @State private var albums: [CatalogAlbum] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .bottom, spacing: 20) {
                    Artwork(url: artist.artworkURL, cornerRadius: 90, symbol: "music.mic").frame(width: 180, height: 180)
                    VStack(alignment: .leading) {
                        Text(artist.name).font(.largeTitle.weight(.bold))
                        if let d = page?.disambiguation { Text(d).foregroundStyle(.secondary) }
                    }
                }
                if let top = page?.topTracks, !top.isEmpty {
                    Section { CatalogTrackList(tracks: Array(top.prefix(10))) } header: { SectionTitle("Top Songs") }
                }
                if !albums.isEmpty {
                    Section { CatalogAlbumGrid(albums: albums) } header: { SectionTitle("Discography") }
                }
                if let bio = page?.biography {
                    Section {
                        Text(stripHTML(bio)).foregroundStyle(.secondary).frame(maxWidth: 700, alignment: .leading)
                    } header: { SectionTitle("About") }
                }
                if let similar = page?.similarArtists, !similar.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 16) {
                                ForEach(similar) { a in
                                    NavigationLink(value: Route.catalogArtist(a)) {
                                        VStack {
                                            Artwork(url: a.artworkURL, cornerRadius: 50, symbol: "music.mic").frame(width: 100, height: 100)
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
        .navigationTitle(artist.name)
        .task(id: artist.key) {
            guard let continuo = model.continuo, let mbid = artist.musicBrainzID else { return }
            page = try? await continuo.catalogArtist(musicBrainzID: mbid)
            albums = (try? await continuo.catalogArtistAlbums(musicBrainzID: mbid)) ?? []
        }
    }
}

/// A catalogue track built from an album's tracklist entry, for Play now.
enum CatalogTrackBuilder {
    static func make(_ entry: CatalogTracklistEntry, album: CatalogAlbum) -> CatalogTrack {
        CatalogTrack(key: entry.recordingID.map { "mb:rec:\($0)" } ?? "\(album.key):\(entry.position)", title: entry.title,
                     artist: album.artist, durationMs: entry.durationMs, albumTitle: album.title,
                     albumReleaseGroupID: album.releaseGroupID, availability: album.availability,
                     recordingID: entry.recordingID, artworkURL: album.artworkURL)
    }
}

/// Catalogue artists in a row of circles, each opening its page (in the library or not).
struct CatalogArtistShelf: View {
    let artists: [CatalogArtist]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 18) {
                ForEach(artists) { artist in
                    NavigationLink(value: Route.catalogArtist(artist)) {
                        VStack {
                            Artwork(url: artist.artworkURL, cornerRadius: 60, symbol: "music.mic").frame(width: 120, height: 120)
                            Text(artist.name).font(.callout).lineLimit(1).frame(width: 120)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}
