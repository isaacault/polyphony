import AppKit
import PolyphonyKit
import SwiftUI

/// The bar along the bottom: what's playing, the transport, the scrubber, volume, and the queue and
/// lyrics panes.
struct PlayerBar: View {
    @Environment(AppModel.self) private var model
    @Binding var inspector: InspectorPane?
    @State private var scrubbing: Double?

    private var player: PlayerController { model.player }

    var body: some View {
        if let entry = player.current {
            VStack(spacing: 0) {
                Divider()
                HStack(spacing: 16) {
                    nowPlaying(entry).frame(width: 280, alignment: .leading)
                    Spacer(minLength: 8)
                    VStack(spacing: 4) {
                        transport
                        scrubber
                    }
                    .frame(maxWidth: 520)
                    Spacer(minLength: 8)
                    trailing.frame(width: 220, alignment: .trailing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .background(.bar)
            .overlay(alignment: .top) {
                if let error = player.error {
                    Text(error).font(.caption).padding(6).background(.red.opacity(0.85), in: Capsule()).foregroundStyle(.white)
                        .offset(y: -14)
                }
            }
        }
    }

    private func nowPlaying(_ entry: QueueEntry) -> some View {
        HStack(spacing: 10) {
            Button { model.nowPlayingExpanded.toggle() } label: {
                Artwork(url: model.artworkURL(for: entry, size: 200), cornerRadius: 4).frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            .help("Now Playing")
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).font(.callout.weight(.medium)).lineLimit(1)
                Text([entry.artist, entry.album].compactMap { $0 }.joined(separator: " — "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            if case let .playNow(stream, _) = entry {
                Image(systemName: "bolt.fill").font(.caption).foregroundStyle(.secondary).help("Play now: streamed while it downloads")
                Button("Keep") {
                    Task {
                        do {
                            try await model.continuo?.keepPlayNow(id: stream.id)
                            model.notice = "Keeping \(entry.title): it'll be added to your library in full quality."
                        } catch {
                            model.handle(error)
                        }
                    }
                }
                .controlSize(.small)
                .help("Add this song to your library")
            }
        }
    }

    private var transport: some View {
        HStack(spacing: 22) {
            Button { player.setShuffle(!player.queue.shuffled) } label: {
                Image(systemName: "shuffle").foregroundStyle(player.queue.shuffled ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            }
            .help("Shuffle")
            Button { player.previous() } label: { Image(systemName: "backward.fill") }
            Button { player.togglePlay() } label: {
                Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 30))
            }
            .overlay { if player.isBuffering { ProgressView().controlSize(.small) } }
            Button { player.next() } label: { Image(systemName: "forward.fill") }
            Button { player.cycleRepeat() } label: {
                Image(systemName: player.queue.repeatMode == .one ? "repeat.1" : "repeat")
                    .foregroundStyle(player.queue.repeatMode == .off ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
            }
            .help("Repeat: \(player.queue.repeatMode.label)")
        }
        .buttonStyle(.plain)
        .font(.system(size: 15))
    }

    private var scrubber: some View {
        HStack(spacing: 8) {
            Text(formatDuration(scrubbing ?? player.position)).monospacedDigit()
            Slider(value: Binding(get: { scrubbing ?? player.position }, set: { scrubbing = $0 }),
                   in: 0...max(player.duration, 1)) { editing in
                if !editing, let scrubbing {
                    player.seek(to: scrubbing)
                    self.scrubbing = nil
                }
            }
            .controlSize(.mini)
            Text(formatDuration(player.duration)).monospacedDigit()
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
    }

    private var trailing: some View {
        HStack(spacing: 14) {
            Button { inspector = inspector == .lyrics ? nil : .lyrics } label: {
                Image(systemName: "quote.bubble").foregroundStyle(inspector == .lyrics ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            }
            .help("Lyrics")
            Button { inspector = inspector == .queue ? nil : .queue } label: {
                Image(systemName: "list.bullet").foregroundStyle(inspector == .queue ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            }
            .help("Queue")
            if player.sleepAt != nil || player.sleepAtEndOfSong {
                Image(systemName: "moon.zzz.fill").foregroundStyle(.tint)
                    .help(player.sleepAtEndOfSong ? "Sleep timer: end of song"
                                                  : "Sleep timer: \(player.sleepAt!.formatted(date: .omitted, time: .shortened))")
            }
            AirPlayButton().frame(width: 22, height: 22).help("AirPlay")
            Image(systemName: "speaker.fill").foregroundStyle(.secondary).font(.caption)
            Slider(value: Binding(get: { Double(player.volume) }, set: { player.volume = Float($0) }), in: 0...1)
                .controlSize(.mini)
                .frame(width: 90)
        }
        .buttonStyle(.plain)
    }
}

/// Up next, and what's already played, in the inspector.
struct QueueView: View {
    @Environment(AppModel.self) private var model
    private var player: PlayerController { model.player }

    var body: some View {
        List {
            if let current = player.queue.current {
                Section("Now Playing") { QueueRow(item: current, dimmed: false) }
            }
            if !player.queue.upNext.isEmpty {
                Section {
                    ForEach(player.queue.upNext) { item in
                        QueueRow(item: item, dimmed: false)
                            .contextMenu { Button("Remove from Queue") { player.remove(item.id) } }
                    }
                    .onMove { from, to in
                        if let first = from.first { player.moveUpNext(from: first, to: to > first ? to - 1 : to) }
                    }
                } header: {
                    HStack {
                        Text(player.queue.title.map { "Up Next · \($0)" } ?? "Up Next")
                        Spacer()
                        Button("Save as Playlist") { saveAsPlaylist() }.buttonStyle(.link).font(.caption)
                        Button("Clear") { player.clearUpNext() }.buttonStyle(.link).font(.caption)
                    }
                }
            }
            if !player.queue.history.isEmpty {
                Section("Played") {
                    ForEach(player.queue.history.reversed()) { QueueRow(item: $0, dimmed: true) }
                }
            }
        }
        .overlay { if player.queue.isEmpty { Placeholder(title: "Nothing queued", symbol: "list.bullet") } }
    }

    /// The queue's library songs, in play order, as a new playlist.
    private func saveAsPlaylist() {
        let ids = ([player.queue.current].compactMap { $0 } + player.queue.upNext).compactMap { $0.entry.libraryTrack?.id }
        let name = player.queue.title.map { "\($0) (queue)" }
            ?? "Queue \(Date().formatted(date: .abbreviated, time: .shortened))"
        Task {
            do {
                try await model.subsonic?.createPlaylist(name: name, trackIDs: ids)
                model.notice = "Saved as “\(name)”"
            } catch {
                model.handle(error)
            }
        }
    }
}

private struct QueueRow: View {
    @Environment(AppModel.self) private var model
    let item: PlayQueue.Item
    let dimmed: Bool

    var body: some View {
        HStack(spacing: 8) {
            Artwork(url: model.artworkURL(for: item.entry, size: 80), cornerRadius: 3).frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 1) {
                Text(item.entry.title).lineLimit(1)
                Text(item.entry.artist ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Text(formatDuration(item.entry.duration)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
        }
        .opacity(dimmed ? 0.55 : 1)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { model.player.jump(to: item.id) }
    }
}

/// Lyrics for what's playing: the file's own (Navidrome), else LRCLIB through continuo. Synced lyrics
/// follow the song, the current line highlighted; click a line to go there.
struct LyricsView: View {
    @Environment(AppModel.self) private var model
    @State private var lyrics: Lyrics?
    @State private var loading = false

    private var player: PlayerController { model.player }
    private var key: String { player.queue.current.map { "\($0.id)" } ?? "" }

    var body: some View {
        Group {
            if let lyrics, !lyrics.lines.isEmpty {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            ForEach(Array(lyrics.lines.enumerated()), id: \.offset) { index, line in
                                Text(line.text.isEmpty ? "♪" : line.text)
                                    .font(.title3.weight(index == currentLine ? .bold : .regular))
                                    .foregroundStyle(lyrics.synced && index != currentLine ? .secondary : .primary)
                                    .id(index)
                                    .onTapGesture {
                                        if let start = line.startMs { player.seek(to: TimeInterval(start) / 1000) }
                                    }
                            }
                            if let source = lyrics.source {
                                Text("Lyrics from \(source)").font(.caption).foregroundStyle(.tertiary).padding(.top, 8)
                            }
                        }
                        .padding(20)
                    }
                    .onChange(of: currentLine) {
                        if let line = currentLine { withAnimation { proxy.scrollTo(line, anchor: .center) } }
                    }
                }
            } else if loading {
                ProgressView()
            } else {
                Placeholder(title: lyrics?.instrumental == true ? "Instrumental" : "No lyrics", symbol: "quote.bubble")
            }
        }
        .task(id: key) { await load() }
    }

    private var currentLine: Int? {
        guard let lyrics, lyrics.synced else { return nil }
        let ms = Int(player.position * 1000)
        return lyrics.lines.lastIndex { ($0.startMs ?? .max) <= ms }
    }

    private func load() async {
        lyrics = nil
        guard let entry = player.current else { return }
        loading = true
        defer { loading = false }
        if let track = entry.libraryTrack, let own = try? await model.subsonic?.lyrics(trackID: track.id) {
            lyrics = own
            return
        }
        if let continuo = model.continuo, let artist = entry.artist {
            lyrics = try? await continuo.lyrics(artist: artist, title: entry.title, album: entry.album,
                                                durationMs: Int(entry.duration * 1000))
        }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Form {
            if let server = model.server {
                Section("Account") {
                    LabeledContent("Signed in as", value: server.subsonicUsername)
                    LabeledContent("Server", value: server.address.absoluteString)
                    LabeledContent("Kind", value: server.kind == .continuo ? "continuo (search, requests, Play now)" : "Subsonic (library only)")
                    Button("Sign Out", role: .destructive) { Task { await model.signOut() } }
                }
            } else {
                Text("Not signed in.")
            }
            if model.isContinuo {
                ScrobblingSection()
                DevicesSection()
            }
            Section("Playback") {
                Picker("Streaming quality", selection: Binding(get: { model.streamingBitrate }, set: { model.setStreamingBitrate($0) })) {
                    Text("Original (as the file is)").tag(0)
                    Text("High (MP3 320 kbps)").tag(320)
                    Text("Normal (MP3 192 kbps)").tag(192)
                    Text("Data saver (MP3 128 kbps)").tag(128)
                }
                Text("Applies from the next song. Transcoding is done by the server.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("About") {
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
                Link("Polyphony on GitHub", destination: URL(string: "https://github.com/isaacault/polyphony")!)
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .padding()
    }
}

/// The menu-bar player: artwork, what's playing, and the transport.
struct MiniPlayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    private var player: PlayerController { model.player }

    var body: some View {
        VStack(spacing: 12) {
            if let entry = player.current {
                Artwork(url: model.artworkURL(for: entry, size: 600), cornerRadius: 8).frame(width: 240, height: 240)
                VStack(spacing: 2) {
                    Text(entry.title).font(.headline).lineLimit(1)
                    Text(entry.artist ?? "").foregroundStyle(.secondary).lineLimit(1)
                }
                ProgressView(value: player.position, total: max(player.duration, 1)).controlSize(.small)
                HStack(spacing: 28) {
                    Button { player.previous() } label: { Image(systemName: "backward.fill") }
                    Button { player.togglePlay() } label: {
                        Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 34))
                    }
                    Button { player.next() } label: { Image(systemName: "forward.fill") }
                }
                .buttonStyle(.plain)
                .font(.title3)
            } else {
                Text("Nothing playing").foregroundStyle(.secondary).padding(.vertical, 30)
            }
            Divider()
            Button("Open Polyphony") {
                openWindow(id: "main")
                NSApp.activate(ignoringOtherApps: true)
            }
            .buttonStyle(.link)
        }
        .padding(16)
        .frame(width: 272)
    }
}

/// Where Play now songs are scrobbled: continuo sends them to ListenBrainz and Last.fm. (Library songs
/// scrobble through Navidrome, set up in its own settings.)
private struct ScrobblingSection: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var links: ScrobbleLinks?
    @State private var listenBrainzToken = ""
    @State private var lastFmToken: String?

    var body: some View {
        Section("Scrobbling (Play now songs)") {
            if let links {
                if links.listenBrainz.available {
                    if links.listenBrainz.connected {
                        LabeledContent("ListenBrainz", value: links.listenBrainz.account ?? "Linked")
                        Button("Unlink ListenBrainz") { run { try await $0.unlinkScrobbler("listenbrainz") } }
                    } else {
                        HStack {
                            SecureField("ListenBrainz user token", text: $listenBrainzToken)
                            Button("Link") { run { try await $0.linkListenBrainz(token: listenBrainzToken) } }
                                .disabled(listenBrainzToken.isEmpty)
                        }
                    }
                }
                if links.lastFm.available {
                    if links.lastFm.connected {
                        LabeledContent("Last.fm", value: links.lastFm.account ?? "Linked")
                        Button("Unlink Last.fm") { run { try await $0.unlinkScrobbler("lastfm") } }
                    } else if let token = lastFmToken {
                        Button("I've Approved Polyphony on Last.fm") {
                            run { try await $0.finishLastFm(token: token) }
                            lastFmToken = nil
                        }
                    } else {
                        Button("Link Last.fm…") {
                            Task {
                                guard let start = try? await model.continuo?.startLastFm() else { return }
                                lastFmToken = start.token
                                openURL(start.url)
                            }
                        }
                    }
                }
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .task { links = try? await model.continuo?.scrobbling() }
    }

    private func run(_ action: @escaping (ContinuoService) async throws -> ScrobbleLinks) {
        Task {
            guard let continuo = model.continuo else { return }
            do {
                links = try await action(continuo)
            } catch {
                model.handle(error)
            }
        }
    }
}

/// Now Playing over the whole window: big artwork beside the lyrics, as Music has it. Esc or the
/// artwork in the bar closes it.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let entry = model.player.current {
            ZStack {
                // The cover, blurred, behind everything.
                Artwork(url: model.artworkURL(for: entry, size: 600), cornerRadius: 0)
                    .scaledToFill()
                    .blur(radius: 60)
                    .opacity(0.45)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                Rectangle().fill(.ultraThinMaterial)
                HStack(spacing: 48) {
                    VStack(alignment: .leading, spacing: 14) {
                        Artwork(url: model.artworkURL(for: entry, size: 1200), cornerRadius: 12)
                            .frame(maxWidth: 460, maxHeight: 460)
                            .shadow(radius: 24, y: 10)
                        Text(entry.title).font(.title.weight(.bold)).lineLimit(2)
                        Text([entry.artist, entry.album].compactMap { $0 }.joined(separator: " — "))
                            .font(.title3).foregroundStyle(.secondary).lineLimit(2)
                    }
                    .frame(maxWidth: 460)
                    LyricsView()
                        .frame(maxWidth: 520, maxHeight: .infinity)
                }
                .padding(48)
                .padding(.bottom, 72)
                VStack {
                    HStack {
                        Spacer()
                        Button { model.nowPlayingExpanded = false } label: {
                            Image(systemName: "chevron.down").font(.title2)
                        }
                        .buttonStyle(.plain)
                        .keyboardShortcut(.cancelAction)
                        .help("Close")
                        .padding(20)
                    }
                    Spacer()
                }
            }
            .onExitCommand { model.nowPlayingExpanded = false }
        }
    }
}

/// The devices signed in to this continuo account, and signing them out.
private struct DevicesSection: View {
    @Environment(AppModel.self) private var model
    @State private var devices: [SignedInDevice] = []

    var body: some View {
        Section("Devices") {
            ForEach(devices) { device in
                HStack {
                    VStack(alignment: .leading) {
                        Text(device.name + (device.current ? " (this Mac)" : ""))
                        Text([device.platform, device.lastSeenAt.map { "last seen \(String($0.prefix(10)))" }]
                            .compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if !device.current {
                        Button("Sign Out") { run { try await $0.signOut(device: device.id) } }
                    }
                }
            }
            if devices.count > 1 {
                Button("Sign Out All Other Devices", role: .destructive) { run { try await $0.signOutOtherDevices() } }
            }
        }
        .task { await load() }
    }

    private func run(_ action: @escaping (ContinuoService) async throws -> Void) {
        Task {
            guard let continuo = model.continuo else { return }
            do {
                try await action(continuo)
                await load()
            } catch {
                model.handle(error)
            }
        }
    }

    private func load() async {
        devices = (try? await model.continuo?.devices()) ?? []
    }
}
