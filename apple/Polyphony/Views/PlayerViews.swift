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
            SpeakerMenu()
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
        .overlay {
            if player.queue.isEmpty { Placeholder(title: "Nothing queued", symbol: "list.bullet", message: "Drag songs here to queue them.") }
        }
        .dropDestination(for: DraggedTracks.self) { drops, _ in
            let tracks = drops.flatMap(\.tracks)
            if player.queue.isEmpty { player.play(tracks) } else { player.addToQueue(tracks.map(QueueEntry.track)) }
            return !tracks.isEmpty
        }
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
        // Tabs, as Mac settings windows have them; each scrolls on its own.
        TabView {
            Form { servers }.formStyle(.grouped)
                .tabItem { Label("Servers", systemImage: "server.rack") }
            Form { playback }.formStyle(.grouped)
                .tabItem { Label("Playback", systemImage: "play.circle") }
            Form { general }.formStyle(.grouped)
                .tabItem { Label("General", systemImage: "gearshape") }
        }
        .frame(width: 540, height: 600)
    }

    @ViewBuilder
    private var servers: some View {
        if let server = model.server {
            Section("Account") {
                LabeledContent("Signed in as", value: server.subsonicUsername)
                LabeledContent("Server", value: server.address.absoluteString)
                LabeledContent("Kind", value: server.kind == .continuo ? "continuo (search, requests, Play now)" : "Subsonic (library only)")
                Button("Sign Out of This Server", role: .destructive) { Task { await model.signOut() } }
            }
            Section("Servers") {
                ForEach(model.servers, id: \.key) { other in
                    HStack {
                        Image(systemName: other.kind == .continuo ? "sparkles" : "server.rack").foregroundStyle(.secondary).frame(width: 18)
                        Text(other.displayName)
                        Spacer()
                        if other.key == server.key {
                            Text("Showing").foregroundStyle(.secondary)
                        } else {
                            Button("Switch") { Task { await model.switchTo(other.key) } }
                        }
                    }
                }
                Button("Add Server…") { model.addingServer = true }
            }
        } else {
            Text("Not signed in.")
        }
        Section("Speakers") {
            TextField("Receiver address", text: Binding(get: { model.receiverAddress }, set: { model.setReceiverAddress($0) }),
                      prompt: Text("http://192.168.0.101"))
            Text("What DLNA speakers are given instead of the server's address: they fetch the music themselves, and many can't do HTTPS. For continuo, its LAN address (http://192.168.0.101).")
                .font(.caption).foregroundStyle(.secondary)
        }
        if model.isContinuo {
            ScrobblingSection()
            DevicesSection()
        }
    }

    @ViewBuilder
    private var playback: some View {
        Section("Streaming") {
            Picker("Streaming quality", selection: Binding(get: { model.streamingBitrate }, set: { model.setStreamingBitrate($0) })) {
                Text("Original (as the file is)").tag(0)
                Text("High (MP3 320 kbps)").tag(320)
                Text("Normal (MP3 192 kbps)").tag(192)
                Text("Data saver (MP3 128 kbps)").tag(128)
            }
            Text("Applies from the next song. Transcoding is done by the server.")
                .font(.caption).foregroundStyle(.secondary)
            Toggle("Offline mode", isOn: Binding(get: { model.offlineMode }, set: { model.setOfflineMode($0) }))
            Text("Only what's downloaded plays; nothing is streamed (as on a plane).")
                .font(.caption).foregroundStyle(.secondary)
        }
        Section("Volume levelling") {
            Picker("Level by", selection: gain(\.mode)) {
                Text("Off").tag(ReplayGain.Mode.off)
                Text("Automatic (album gain for whole albums)").tag(ReplayGain.Mode.auto)
                Text("Track gain").tag(ReplayGain.Mode.track)
                Text("Album gain").tag(ReplayGain.Mode.album)
            }
            if model.replayGain.mode != .off {
                Stepper(value: gain(\.preampDb), in: -12...12, step: 1) {
                    LabeledContent("Pre-amp", value: String(format: "%+.0f dB", model.replayGain.preampDb))
                }
                Toggle("Prevent clipping", isOn: gain(\.preventClipping))
            }
            Text("ReplayGain tags from the files: songs too loud are turned down to match (never up past full volume).")
                .font(.caption).foregroundStyle(.secondary)
        }
        Section("Output") {
            Toggle("Pause when headphones disconnect",
                   isOn: Binding(get: { model.pauseOnDisconnect }, set: { model.setPauseOnDisconnect($0) }))
        }
    }

    @ViewBuilder
    private var general: some View {
        Section("Appearance") {
            Toggle("Artwork colours in Now Playing",
                   isOn: Binding(get: { model.artworkColours }, set: { model.setArtworkColours($0) }))
        }
        Section("Favourites") {
            Picker("Mark songs with", selection: Binding(get: { model.usesRatings }, set: { model.setUsesRatings($0) })) {
                Text("A heart (favourite or not)").tag(false)
                Text("Stars (1 to 5)").tag(true)
            }
        }
        Section("About") {
            LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
            Link("Polyphony on GitHub", destination: URL(string: "https://github.com/isaacault/polyphony")!)
            Text("Something wrong? Help > Save Diagnostics writes a file to send (no passwords or tokens in it).")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func gain<V>(_ field: WritableKeyPath<ReplayGain.Settings, V>) -> Binding<V> {
        Binding(get: { model.replayGain[keyPath: field] }, set: { model.replayGain[keyPath: field] = $0 })
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
    @State private var palette: ArtworkPalette?

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
                // Its colours over it (Settings > General).
                if model.artworkColours, let palette {
                    LinearGradient(colors: [Color(palette.vibrant).opacity(0.55), Color(palette.darkMuted).opacity(0.75)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                        .transition(.opacity)
                }
                Rectangle().fill(.ultraThinMaterial)
                HStack(spacing: 48) {
                    VStack(alignment: .leading, spacing: 14) {
                        Artwork(url: model.artworkURL(for: entry, size: 1200), cornerRadius: 12)
                            .frame(maxWidth: 460, maxHeight: 460)
                            .shadow(radius: 24, y: 10)
                        Text(entry.title).font(.title.weight(.bold)).lineLimit(2)
                        Text([entry.artist, entry.album].compactMap { $0 }.joined(separator: " — "))
                            .font(.title3).foregroundStyle(.secondary).lineLimit(2)
                        NowPlayingActions(entry: entry)
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
            .tint(model.artworkColours ? palette.map { Color($0.vibrant) } : nil)
            .animation(.easeInOut(duration: 0.6), value: palette)
            .task(id: model.artworkURL(for: entry, size: 300)) {
                guard let url = model.artworkURL(for: entry, size: 300) else { palette = nil; return }
                palette = await ArtworkColors.palette(for: url)
            }
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

/// Where the music plays: this Mac, or a DLNA speaker on the network (the Yamaha, …).
struct SpeakerMenu: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Menu {
            Button { model.play(on: nil) } label: {
                Label("This Mac", systemImage: model.speaker == nil ? "checkmark" : "laptopcomputer")
            }
            if !model.speakers.renderers.isEmpty { Divider() }
            ForEach(model.speakers.renderers) { renderer in
                Button { model.play(on: renderer) } label: {
                    Label([renderer.name, renderer.model].compactMap { $0 }.joined(separator: " · "),
                          systemImage: model.speaker?.udn == renderer.udn ? "checkmark" : "hifispeaker")
                }
            }
            Divider()
            Button(model.speakers.searching ? "Looking for Speakers…" : "Look for Speakers") { model.speakers.search() }
                .disabled(model.speakers.searching)
            if model.receiverAddress.isEmpty {
                Text("Set a receiver address in Settings if a speaker can't play")
            }
        } label: {
            Image(systemName: model.speaker == nil ? "hifispeaker" : "hifispeaker.fill")
                .foregroundStyle(model.speaker == nil ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help(model.speaker.map { "Playing on \($0.name)" } ?? "Play on a speaker")
        .onAppear { if model.speakers.renderers.isEmpty { model.speakers.search() } }
    }
}

/// Under the big cover: where it's playing from and on, the song's quality and details, the heart or
/// stars, and the song's menu (as the Android app's Now Playing).
private struct NowPlayingActions: View {
    @Environment(AppModel.self) private var model
    let entry: QueueEntry
    @State private var showingInfo = false
    /// What was set here since the song was queued (the queue's copy of the song doesn't change).
    @State private var hearts: [String: Bool] = [:]
    @State private var ratings: [String: Int] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let context = model.player.queue.context {
                Button { open(context) } label: {
                    Label("Playing from \(context.title)", systemImage: "music.note.list")
                }
                .buttonStyle(.link)
                .disabled(context.kind == .genre || context.kind == .favourites)
            }
            if let speaker = model.speaker {
                Label("Playing on \(speaker.name)", systemImage: "hifispeaker.fill").foregroundStyle(.tint)
            }
            HStack(spacing: 14) {
                if let track = entry.libraryTrack {
                    if let chip = AudioQuality.trackChip(track, albumBadge: nil) {
                        Button { showingInfo.toggle() } label: { QualityChip(label: chip) }
                            .buttonStyle(.plain)
                            .help("Song Info")
                            .popover(isPresented: $showingInfo) { SongInfo(track: track).padding() }
                    } else {
                        Button { showingInfo.toggle() } label: { Image(systemName: "info.circle") }
                            .buttonStyle(.plain)
                            .help("Song Info")
                            .popover(isPresented: $showingInfo) { SongInfo(track: track).padding() }
                    }
                    if model.usesRatings {
                        Menu {
                            ForEach(1...5, id: \.self) { stars in
                                Button(String(repeating: "★", count: stars)) { rate(track, stars) }
                            }
                            Button("No Rating") { rate(track, 0) }
                        } label: {
                            let stars = ratings[track.id] ?? track.rating ?? 0
                            Text(String(repeating: "★", count: stars) + String(repeating: "☆", count: 5 - stars))
                        }
                        .menuStyle(.borderlessButton)
                        .fixedSize()
                    } else {
                        let starred = hearts[track.id] ?? track.starred
                        Button {
                            hearts[track.id] = !starred
                            Task {
                                try? await model.subsonic?.setStarred(.track(track.id), !starred)
                                model.downloads.libraryChanged()
                            }
                        } label: { Image(systemName: starred ? "heart.fill" : "heart") }
                            .buttonStyle(.plain)
                            .help(starred ? "Remove from Favourites" : "Add to Favourites")
                    }
                    Menu {
                        if let albumID = track.albumID { Button("Go to Album") { go(.album(albumID)) } }
                        if let artistID = track.artistID { Button("Go to Artist") { go(.artist(artistID)) } }
                        Divider()
                        AddToPlaylistMenu(trackIDs: [track.id])
                        if !model.downloads.isDownloaded(track.id) {
                            Button("Download") { model.downloads.download([track]) }
                        }
                        Divider()
                        SpeedMenu()
                    } label: { Image(systemName: "ellipsis.circle") }
                        .menuStyle(.borderlessButton)
                        .menuIndicator(.hidden)
                        .fixedSize()
                        .help("More")
                }
            }
            .font(.title3)
        }
        .foregroundStyle(.secondary)
    }

    private func rate(_ track: Track, _ stars: Int) {
        ratings[track.id] = stars
        Task {
            try? await model.subsonic?.setRating(id: track.id, stars)
            model.notice = stars == 0 ? "Rating removed" : "Rated \(String(repeating: "★", count: stars))"
        }
    }

    private func go(_ route: Route) {
        model.nowPlayingExpanded = false
        model.path.append(route)
    }

    private func open(_ context: PlayContext) {
        switch context.kind {
        case .album: go(.album(context.id))
        case .playlist: go(.playlist(context.id))
        case .artist: go(.artist(context.id))
        case .genre, .favourites: break
        }
    }
}

/// The song's file: format, resolution, bitrate, size.
struct SongInfo: View {
    let track: Track

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
            row("Format", track.container?.uppercased())
            row("Resolution", [track.bitDepth.map { "\($0)-bit" }, track.sampleRateHz.map { String(format: "%g kHz", Double($0) / 1000) }]
                .compactMap { $0 }.joined(separator: " / ").nilIfEmpty)
            row("Bitrate", track.bitRateKbps.map { "\($0) kbps" })
            row("Size", track.sizeBytes.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) })
            row("Length", formatDuration(track.duration))
            if let rg = track.replayGain {
                row("ReplayGain", [rg.trackGainDb.map { String(format: "track %+.1f dB", $0) }, rg.albumGainDb.map { String(format: "album %+.1f dB", $0) }]
                    .compactMap { $0 }.joined(separator: ", ").nilIfEmpty)
            }
        }
        .font(.callout)
        .frame(minWidth: 220)
    }

    @ViewBuilder
    private func row(_ name: String, _ value: String?) -> some View {
        if let value {
            GridRow {
                Text(name).foregroundStyle(.secondary)
                Text(value).textSelection(.enabled)
            }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// Speed ▸ 0.75×, 1×, 1.25×, 1.5×, 2× (Now Playing's menu, and the Controls menu).
struct SpeedMenu: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Menu("Speed") {
            ForEach(PlayerController.speeds, id: \.self) { speed in
                Button {
                    model.player.speed = speed
                } label: {
                    if model.player.speed == speed { Label(Self.label(speed), systemImage: "checkmark") } else { Text(Self.label(speed)) }
                }
            }
        }
    }

    static func label(_ speed: Float) -> String { String(format: "%g×", speed) }
}
