import AppKit
import PolyphonyKit
import SwiftUI
import UniformTypeIdentifiers

/// Settings > General > Appearance and the sidebar.
struct AppearanceSettings: View {
    @Environment(AppModel.self) private var model
    @AppStorage("appearance.theme") private var theme: ThemeMode = .system
    @AppStorage("appearance.customAccent") private var customAccent = false
    @AppStorage("appearance.accent") private var accent = 0x6750A4
    @AppStorage("appearance.pureBlack") private var pureBlack = false
    @AppStorage("appearance.blurredBackground") private var blurredBackground = true
    @AppStorage("appearance.artworkShape") private var artworkShape: ArtworkShape = .rounded
    @AppStorage("appearance.gridColumns") private var gridColumns = 0
    @AppStorage("appearance.trackNumbers") private var trackNumbers = true
    @AppStorage("appearance.ratingStyle") private var ratingStyle: RatingStyle = .compact
    @AppStorage("sidebar.hidden") private var hidden = ""
    @AppStorage("sidebar.opensOn") private var opensOn = SidebarItem.home.rawValue
    @State private var artworkCacheMB = ArtworkCache.limitMB

    private static let optional: [SidebarItem] = [.explore, .artists, .albums, .songs, .playlists, .genres, .favourites, .downloads]

    private func sidebarBinding(_ item: SidebarItem) -> Binding<Bool> {
        Binding(get: { !hidden.split(separator: ",").contains(Substring(item.rawValue)) }, set: { shown in
            var set = Set(hidden.split(separator: ",").map(String.init))
            if shown { set.remove(item.rawValue) } else { set.insert(item.rawValue) }
            hidden = set.sorted().joined(separator: ",")
        })
    }

    var body: some View {
        Section("Theme") {
            Picker("Mode", selection: $theme) {
                ForEach(ThemeMode.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            Toggle("Use the system accent colour", isOn: Binding(get: { !customAccent }, set: { customAccent = !$0 }))
            if customAccent {
                ColorPicker("Accent colour", selection: Binding(get: { Color(rgb: accent) }, set: { accent = $0.rgb }), supportsOpacity: false)
            }
            Toggle("Pure black dark theme", isOn: $pureBlack)
        }
        Section("Player") {
            Toggle("Artwork colours in Now Playing",
                   isOn: Binding(get: { model.artworkColours }, set: { model.setArtworkColours($0) }))
            Toggle("Blurred artwork background", isOn: $blurredBackground)
            Picker("Artwork shape", selection: $artworkShape) {
                ForEach(ArtworkShape.allCases, id: \.self) { Text($0.label).tag($0) }
            }
        }
        Section("Library") {
            Picker("Grid columns", selection: $gridColumns) {
                Text("Auto").tag(0)
                ForEach(3...8, id: \.self) { Text("\($0)").tag($0) }
            }
            Toggle("Show track numbers", isOn: $trackNumbers)
            Picker("Ratings on songs", selection: $ratingStyle) {
                ForEach(RatingStyle.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            Picker("Opens on", selection: $opensOn) {
                ForEach([SidebarItem.home] + Self.optional) { Text($0.title).tag($0.rawValue) }
            }
            DisclosureGroup("Sidebar") {
                ForEach(Self.optional) { item in
                    Toggle(item.title, isOn: sidebarBinding(item))
                }
            }
        }
        Section("Artwork") {
            Picker("Artwork cache size", selection: Binding(get: { artworkCacheMB }, set: {
                artworkCacheMB = $0
                ArtworkCache.set(limitMB: $0)
            })) {
                ForEach([128, 256, 512, 1024, 2048], id: \.self) { Text($0 >= 1024 ? "\($0 / 1024) GB" : "\($0) MB").tag($0) }
            }
            Button("Clear Artwork Cache") {
                ArtworkCache.clear()
                model.notice = "Artwork cache cleared"
            }
        }
    }
}

/// Settings > General > Backup & restore.
struct BackupSettings: View {
    @Environment(AppModel.self) private var model
    @State private var failure: String?

    var body: some View {
        Section("Backup & restore") {
            Text("A single file with your settings and servers. It holds plain Subsonic servers' passwords, so keep it private. continuo servers are signed in to again.")
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Button("Export Backup…") { export() }
                Button("Restore Backup…") { restore() }
            }
            if let failure {
                Text(failure).font(.caption).foregroundStyle(.red)
            }
        }
    }

    private func export() {
        let panel = NSSavePanel()
        let format = DateFormatter()
        format.dateFormat = "yyyyMMdd"
        panel.nameFieldStringValue = "polyphony-backup-\(format.string(from: Date())).json"
        panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task {
            do {
                try await model.backup().write(to: url, options: .atomic)
                failure = nil
                model.notice = "Backup saved"
            } catch {
                failure = "Could not save the backup: \(ErrorText.of(error))"
            }
        }
    }

    private func restore() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task {
            do {
                try await model.restore(try Data(contentsOf: url))
                failure = nil
            } catch {
                failure = String(describing: error)
            }
        }
    }
}

/// Downloads' options, under its rules (the Android app's Settings > Downloads).
struct DownloadOptions: View {
    @Environment(AppModel.self) private var model
    let downloads: Downloads

    var body: some View {
        Toggle("Download on unmetered connections only",
               isOn: Binding(get: { downloads.unmeteredOnly }, set: { downloads.setUnmeteredOnly($0) }))
        Toggle("Only while on mains power",
               isOn: Binding(get: { downloads.onlyWhileCharging }, set: { downloads.setOnlyWhileCharging($0) }))
        Picker("Parallel downloads", selection: Binding(get: { downloads.parallel }, set: { downloads.setParallel($0) })) {
            ForEach(1...5, id: \.self) { Text("\($0)").tag($0) }
        }
        .fixedSize()
        Picker("Default download quality", selection: Binding(get: { downloads.qualityProfileID }, set: { downloads.setQualityProfile($0) })) {
            Text("Original").tag(String?.none)
            ForEach(model.streaming.allProfiles.filter { !$0.isPassthrough }) { Text($0.name).tag(String?.some($0.id)) }
        }
        .fixedSize()
        Toggle("Remove songs a rule no longer matches",
               isOn: Binding(get: { downloads.removeUnmatched }, set: { downloads.setRemoveUnmatched($0) }))
        Toggle("Evict least recently played first",
               isOn: Binding(get: { downloads.evictLeastPlayed }, set: { downloads.setEvictLeastPlayed($0) }))
        Picker("Check rules every", selection: Binding(get: { downloads.checkEveryHours }, set: { downloads.setCheckEveryHours($0) })) {
            ForEach([1, 6, 12, 24], id: \.self) { Text($0 == 1 ? "hour" : "\($0) hours").tag($0) }
        }
        .fixedSize()
        Toggle("Prefer downloaded files for playback",
               isOn: Binding(get: { downloads.preferDownloaded }, set: { downloads.setPreferDownloaded($0) }))
    }
}
