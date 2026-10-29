import PolyphonyKit
import SwiftUI

/// The album page's download button: Download, its progress, or Downloaded (click to remove).
struct DownloadButton: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]

    var body: some View {
        let downloads = model.downloads
        let have = downloads.index.count(of: tracks)
        let going = tracks.filter { downloads.active[$0.id] != nil }.count
        if going > 0 {
            Label("Downloading \(have)/\(tracks.count)", systemImage: "arrow.down.circle.dotted")
                .foregroundStyle(.secondary)
        } else if have == tracks.count, !tracks.isEmpty {
            Button { downloads.remove(tracks.map(\.id)) } label: { Image(systemName: "arrow.down.circle.fill") }
                .help("Downloaded: click to remove from this Mac")
        } else {
            Button { downloads.download(tracks) } label: { Image(systemName: "arrow.down.circle") }
                .help(have > 0 ? "Download the rest (\(have) of \(tracks.count) on this Mac)" : "Download to this Mac")
        }
    }
}

/// What's on this Mac: albums of downloaded songs, playable with no network.
struct DownloadsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let downloads = model.downloads
        let albums = downloads.index.albums
        List {
            if !downloads.active.isEmpty {
                Section { Text("Downloading \(downloads.active.count) songs…").foregroundStyle(.secondary) }
            }
            Section {
                ForEach(albums) { album in
                    HStack(spacing: 10) {
                        Artwork(url: model.coverURL(album.coverArtID, size: 100), cornerRadius: 4).frame(width: 44, height: 44)
                        VStack(alignment: .leading) {
                            Text(album.name)
                            Text([album.artist, "\(album.tracks.count) songs",
                                  ByteCountFormatter.string(fromByteCount: album.bytes, countStyle: .file)]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { model.player.play(album.tracks, title: album.name) } label: { Image(systemName: "play.fill") }
                            .buttonStyle(.borderless)
                    }
                    .contextMenu {
                        Button("Play") { model.player.play(album.tracks, title: album.name) }
                        Button("Remove from This Mac", role: .destructive) { downloads.remove(album.tracks.map(\.id)) }
                    }
                }
            } header: {
                if !albums.isEmpty {
                    Text("\(ByteCountFormatter.string(fromByteCount: downloads.usedBytes, countStyle: .file)) on this Mac")
                }
            }
        }
        .overlay {
            if albums.isEmpty && downloads.active.isEmpty {
                Placeholder(title: "Nothing downloaded", symbol: "arrow.down.to.line",
                            message: "Download albums to play them without a connection.")
            }
        }
        .navigationTitle("Downloads")
    }
}
