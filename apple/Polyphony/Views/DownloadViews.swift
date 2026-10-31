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
            if tracks.contains(where: { downloads.index.entries[$0.id]?.pinned == false }) {
                // Removing would only have the rule download them again: the rule is where it stops.
                Button { model.go(to: .downloads) } label: { Image(systemName: "arrow.down.circle.fill") }
                    .help("Kept downloaded by a rule: open Downloads to stop it")
            } else {
                Button { downloads.remove(tracks.map(\.id)) } label: { Image(systemName: "arrow.down.circle.fill") }
                    .help("Downloaded: click to remove from this Mac")
            }
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
                ForEach(downloads.rules) { rule in
                    HStack {
                        Toggle(isOn: Binding(get: { rule.enabled }, set: { downloads.setEnabled(rule.id, $0) })) {
                            VStack(alignment: .leading) {
                                Text(rule.target.label)
                                let kept = downloads.index.entries.values.filter { $0.ruleID == rule.id }.count
                                Text(rule.enabled ? "\(kept) songs kept" : "Paused").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .toggleStyle(.switch)
                        Button(role: .destructive) { downloads.stopKeeping(rule.id) } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless)
                            .help("Stop keeping this downloaded (its songs go, unless downloaded by hand)")
                    }
                }
                Menu("Add Rule") {
                    Button("Favourite Songs") { downloads.keep(.favourites) }
                    Menu("Newest Albums") {
                        ForEach([10, 25, 50], id: \.self) { n in Button("Last \(n) Added") { downloads.keep(.recentlyAdded(albums: n)) } }
                    }
                    Menu("Most Played Albums") {
                        ForEach([10, 25, 50], id: \.self) { n in Button("Top \(n)") { downloads.keep(.mostPlayed(albums: n)) } }
                    }
                    Menu("Recently Played Albums") {
                        ForEach([10, 25], id: \.self) { n in Button("Last \(n)") { downloads.keep(.recentlyPlayed(albums: n)) } }
                    }
                    Divider()
                    Text("Playlists, albums, artists and genres: “Keep Downloaded” on their page")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            } header: {
                HStack {
                    Text("Rules")
                    Spacer()
                    if downloads.checkingRules {
                        ProgressView().controlSize(.mini)
                    } else if !downloads.rules.isEmpty {
                        Button("Check Now") { Task { await downloads.checkRules() } }.buttonStyle(.link).font(.caption)
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Rules keep themselves downloaded: new songs in them download, ones that leave go (unless you downloaded them by hand).")
                    Picker("Storage limit", selection: Binding(get: { downloads.limitGB }, set: { downloads.setLimit(gb: $0) })) {
                        Text("No limit").tag(0)
                        ForEach([5, 10, 25, 50, 100, 250], id: \.self) { Text("\($0) GB").tag($0) }
                    }
                    .fixedSize()
                    if downloads.limitGB > 0 {
                        Text("Over the limit, the songs rules keep that were played least recently go first. Downloads by hand stay.")
                    }
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            if albums.isEmpty && downloads.active.isEmpty {
                Section {
                    Label("Nothing downloaded yet. Download albums to play them without a connection, or keep a playlist downloaded from its page.",
                          systemImage: "arrow.down.to.line")
                        .foregroundStyle(.secondary)
                }
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
                        if album.tracks.contains(where: { downloads.index.entries[$0.id]?.pinned == false }) {
                            Text("Kept by a download rule (stop it above)")
                        } else {
                            Button("Remove from This Mac", role: .destructive) { downloads.remove(album.tracks.map(\.id)) }
                        }
                    }
                }
            } header: {
                if !albums.isEmpty {
                    Text("\(ByteCountFormatter.string(fromByteCount: downloads.usedBytes, countStyle: .file)) on this Mac")
                }
            }
        }
        .navigationTitle("Downloads")
    }
}
