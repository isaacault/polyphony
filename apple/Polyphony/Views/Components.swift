import PolyphonyKit
import SwiftUI

/// Album and artist art, with a quiet placeholder while it loads or when there is none.
struct Artwork: View {
    let url: URL?
    var cornerRadius: CGFloat = 6
    var symbol = "music.note"

    var body: some View {
        AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
            switch phase {
            case let .success(image):
                image.resizable().aspectRatio(contentMode: .fill)
            default:
                ZStack {
                    Rectangle().fill(.quaternary)
                    Image(systemName: symbol).font(.title2).foregroundStyle(.tertiary)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

/// The small quality label ("HI-RES", "FLAC", "16-BIT") beside a title.
struct QualityChip: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.system(size: 9, weight: .semibold))
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(.secondary, lineWidth: 0.8))
            .foregroundStyle(.secondary)
    }
}

/// One album in a grid: cover, title, artist.
struct AlbumTile: View {
    @Environment(AppModel.self) private var model
    let album: Album

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Artwork(url: model.coverURL(album.coverArtID ?? album.id, size: 400))
            Text(album.name).font(.callout.weight(.medium)).lineLimit(1)
            Text(album.artistName ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .contentShape(Rectangle())
    }
}

/// An album from the catalogue: like [AlbumTile], with where it stands (in the library, requested…).
struct CatalogAlbumTile: View {
    let album: CatalogAlbum

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Artwork(url: album.artworkURL)
                .overlay(alignment: .bottomTrailing) { AvailabilityBadge(availability: album.availability).padding(4) }
            Text(album.title).font(.callout.weight(.medium)).lineLimit(1)
            Text([album.artist, album.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .contentShape(Rectangle())
    }
}

struct AvailabilityBadge: View {
    let availability: Availability

    var body: some View {
        switch availability {
        case .inLibrary:
            EmptyView()
        case let .partial(have, expected, _, _):
            badge("\(have)/\(expected)", "circle.lefthalf.filled")
        case .requested:
            badge("Requested", "arrow.down.circle")
        case .requestable, .onServer:
            badge(nil, "plus.circle")
        case .catalogOnly:
            EmptyView()
        }
    }

    private func badge(_ text: String?, _ symbol: String) -> some View {
        Label { if let text { Text(text) } } icon: { Image(systemName: symbol) }
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(.thinMaterial, in: Capsule())
    }
}

/// A grid of albums that open their page.
struct AlbumGrid: View {
    let albums: [Album]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 18)], alignment: .leading, spacing: 22) {
            ForEach(albums) { album in
                NavigationLink(value: Route.album(album.id)) { AlbumTile(album: album) }
                    .buttonStyle(.plain)
                    .albumContextMenu(album)
            }
        }
    }
}

/// Where a click goes inside the library's navigation stack.
enum Route: Hashable {
    case album(String)
    case artist(String)
    case playlist(String)
    case genre(String)
    case catalogAlbum(CatalogAlbum)
    case catalogArtist(CatalogArtist)
    case discoveryPlaylist(DiscoveryPlaylist)
}

func formatDuration(_ seconds: TimeInterval) -> String {
    guard seconds.isFinite, seconds > 0 else { return "–:––" }
    let s = Int(seconds.rounded())
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}

func formatLong(_ seconds: TimeInterval) -> String {
    let minutes = Int(seconds / 60)
    return minutes >= 60 ? "\(minutes / 60) hr \(minutes % 60) min" : "\(minutes) min"
}

/// Empty and error states, centred.
struct Placeholder: View {
    let title: String
    var symbol = "music.note.list"
    var message: String?

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: symbol)
        } description: {
            if let message { Text(message) }
        }
    }
}

extension View {
    /// The album's right-click menu: play, shuffle, play next, add to queue, favourite.
    func albumContextMenu(_ album: Album) -> some View {
        modifier(AlbumContextMenu(album: album))
    }

    func trackContextMenu(_ track: Track, in tracks: [Track] = []) -> some View {
        modifier(TrackContextMenu(track: track, tracks: tracks))
    }
}

private struct AlbumContextMenu: ViewModifier {
    @Environment(AppModel.self) private var model
    let album: Album

    func body(content: Content) -> some View {
        content.contextMenu {
            Button("Play") { Task { await load { model.player.play($0, title: album.name) } } }
            Button("Shuffle") { Task { await load { model.player.play($0, shuffle: true, title: album.name) } } }
            Button("Play Next") { Task { await load { model.player.playNext($0.map(QueueEntry.track)) } } }
            Button("Add to Queue") { Task { await load { model.player.addToQueue($0.map(QueueEntry.track)) } } }
            Divider()
            Button(album.starred ? "Remove from Favourites" : "Add to Favourites") {
                Task { try? await model.subsonic?.setStarred(.album(album.id), !album.starred) }
            }
        }
    }

    private func load(_ then: @escaping ([Track]) -> Void) async {
        guard let subsonic = model.subsonic else { return }
        do {
            then(try await subsonic.album(id: album.id).tracks)
        } catch {
            model.handle(error)
        }
    }
}

private struct TrackContextMenu: ViewModifier {
    @Environment(AppModel.self) private var model
    let track: Track
    let tracks: [Track]

    func body(content: Content) -> some View {
        content.contextMenu {
            Button("Play") {
                let list = tracks.isEmpty ? [track] : tracks
                model.player.play(list, startAt: list.firstIndex(of: track) ?? 0)
            }
            Button("Play Next") { model.player.playNext([.track(track)]) }
            Button("Add to Queue") { model.player.addToQueue([.track(track)]) }
            Divider()
            if let albumID = track.albumID {
                NavigationLink("Go to Album", value: Route.album(albumID))
            }
            if let artistID = track.artistID {
                NavigationLink("Go to Artist", value: Route.artist(artistID))
            }
            Divider()
            Button(track.starred ? "Remove from Favourites" : "Add to Favourites") {
                Task { try? await model.subsonic?.setStarred(.track(track.id), !track.starred) }
            }
            AddToPlaylistMenu(trackIDs: [track.id])
        }
    }
}

/// "Add to Playlist ▸" with the user's playlists and a new one.
struct AddToPlaylistMenu: View {
    @Environment(AppModel.self) private var model
    let trackIDs: [String]
    @State private var playlists: [Playlist] = []

    var body: some View {
        Menu("Add to Playlist") {
            ForEach(playlists) { playlist in
                Button(playlist.name) {
                    Task {
                        // Songs already there aren't added twice.
                        let existing = (try? await model.subsonic?.playlist(id: playlist.id).tracks.map(\.id)) ?? []
                        let fresh = trackIDs.filter { !Set(existing).contains($0) }
                        if fresh.isEmpty {
                            model.notice = "Already in \(playlist.name)"
                        } else {
                            try? await model.subsonic?.updatePlaylist(id: playlist.id, adding: fresh)
                            model.notice = "Added to \(playlist.name)"
                        }
                    }
                }
            }
        }
        .task { playlists = (try? await model.subsonic?.playlists()) ?? [] }
    }
}
