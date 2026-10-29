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
            Artwork(url: model.artworkURL(for: entry, size: 200), cornerRadius: 4).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).font(.callout.weight(.medium)).lineLimit(1)
                Text([entry.artist, entry.album].compactMap { $0 }.joined(separator: " — "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            if case .playNow = entry {
                Image(systemName: "bolt.fill").font(.caption).foregroundStyle(.secondary).help("Play now: streamed while it downloads")
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
